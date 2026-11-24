package com.aicarcompanion.enet.dtc

import com.aicarcompanion.enet.Hsfz
import com.aicarcompanion.enet.HsfzFrameReader
import com.aicarcompanion.enet.HsfzProtocolException
import com.aicarcompanion.enet.TcpChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

/** How one request on one module ended. Raw response bytes are kept whenever any arrived. */
sealed interface UdsOutcome {
    val elapsedMillis: Long

    data class Positive(val response: ByteArray, override val elapsedMillis: Long) : UdsOutcome
    data class Negative(val nrc: Int, val response: ByteArray, override val elapsedMillis: Long) : UdsOutcome {
        val unsupported get() = Uds.isUnsupported(nrc)
    }
    /** No final answer before the deadline. [pendingCount] counts 7F xx 78 replies received first. */
    data class Timeout(val pendingCount: Int, override val elapsedMillis: Long) : UdsOutcome
    /** HSFZ-level rejection (NACK or 0x40..0x45), e.g. unknown destination. */
    data class GatewayError(val control: Int, override val elapsedMillis: Long) : UdsOutcome
    data class TransportError(val reason: String, override val elapsedMillis: Long) : UdsOutcome
    /** Not sent, because an earlier request on this connection failed and the stream can't be trusted. */
    data class NotSent(val reason: String) : UdsOutcome { override val elapsedMillis = 0L }

    fun summary(): String = when (this) {
        is Positive -> "Positive response"
        is Negative -> "Negative response NRC 0x%02X (%s)".format(nrc, Uds.nrcName(nrc))
        is Timeout -> if (pendingCount > 0) "No final reply after $pendingCount response-pending message(s)" else "No reply (timeout)"
        is GatewayError -> "Gateway error 0x%04X (%s)".format(control, gatewayErrorName(control))
        is TransportError -> "Connection error: $reason"
        is NotSent -> "Not sent: $reason"
    }
}

fun gatewayErrorName(control: Int) = when (control) {
    Hsfz.CTRL_NACK -> "negative acknowledgement"
    0x0040 -> "wrong tester address"
    0x0041 -> "wrong control word"
    0x0042 -> "wrong frame format"
    0x0043 -> "unknown destination"
    0x0044 -> "message too large"
    0x0045 -> "gateway not ready"
    else -> "unknown"
}

data class Timing(
    /** P2: time allowed for the first answer. Matches the tester's existing 3 s module deadline. */
    val responseMillis: Long = 3_000,
    /** P2*: time allowed after each 7F xx 78 "response pending". ISO 14229-2 default is 5 s. */
    val pendingMillis: Long = 5_000,
    /** Upper bound on the whole exchange, however many pending messages arrive. */
    val maxTotalMillis: Long = 30_000,
)

/**
 * One HSFZ connection to one ECU (physical addressing). Requests are strictly sequential: each
 * waits for its own final answer. A reply only counts when it comes from [module] to the tester
 * and carries the expected response SID (and sub-function). Anything else is logged and ignored.
 *
 * After a timeout, transport error or protocol error the session is closed for further
 * requests: a late reply could otherwise be mistaken for the answer to the next request, and
 * the request is never blindly re-sent on the same connection.
 */
class UdsSession(
    private val tcp: TcpChannel,
    val module: Int,
    private val clock: () -> Long,
    private val timing: Timing = Timing(),
    private val log: (String) -> Unit = {},
) {
    private val reader = HsfzFrameReader()
    private var brokenReason: String? = null

    val usable get() = brokenReason == null

    suspend fun request(request: ReadRequest): UdsOutcome = exchange(request.uds, request.label)

    /** Only [ClearGate] can produce a [ClearCommand]; see BmwFaultMemory.clear. */
    internal suspend fun clear(command: ClearCommand): UdsOutcome = exchange(command.uds, "ClearDiagnosticInformation (14 FF FF FF)")

    private suspend fun exchange(uds: ByteArray, label: String): UdsOutcome {
        brokenReason?.let { return UdsOutcome.NotSent(it) }
        val sid = uds[0].toInt() and 0xFF
        val sub = if (sid == Uds.SID_READ_DTC) uds[1].toInt() and 0xFF else null
        val frame = Hsfz.frame(Hsfz.CTRL_DIAG, byteArrayOf(Hsfz.TESTER_ADDRESS.toByte(), module.toByte()) + uds)
        val t0 = clock()
        try {
            tcp.write(frame)
        } catch (e: IOException) {
            return broken(UdsOutcome.TransportError(e.message ?: e.javaClass.simpleName, clock() - t0))
        }
        log("→ 0x%02X %s [%s]".format(module, label, uds.hexString()))
        var deadline = t0 + timing.responseMillis
        val hardDeadline = t0 + timing.maxTotalMillis
        var pending = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val remaining = minOf(deadline, hardDeadline) - clock()
            if (remaining <= 0) return broken(UdsOutcome.Timeout(pending, clock() - t0))
            val bytes = try {
                tcp.read(Hsfz.MAX_PAYLOAD + Hsfz.HEADER_SIZE, remaining)
            } catch (e: IOException) {
                return broken(UdsOutcome.TransportError(e.message ?: e.javaClass.simpleName, clock() - t0))
            } ?: return broken(UdsOutcome.Timeout(pending, clock() - t0))
            if (bytes.isEmpty()) return broken(UdsOutcome.TransportError("Gateway closed the connection", clock() - t0))
            val frames = try { reader.feed(bytes) } catch (e: HsfzProtocolException) {
                return broken(UdsOutcome.TransportError(e.message ?: "protocol error", clock() - t0))
            }
            for (f in frames) {
                when (f.control) {
                    Hsfz.CTRL_ALIVE_CHECK -> { log("← alive check; answering"); runCatching { tcp.write(Hsfz.ALIVE_RESPONSE) } }
                    Hsfz.CTRL_ACK -> Unit // Transport echo of our request, not an answer.
                    Hsfz.CTRL_NACK, in 0x40..0x45 -> return broken(UdsOutcome.GatewayError(f.control, clock() - t0))
                    Hsfz.CTRL_DIAG -> {
                        val p = f.payload
                        if (p.size < 3) continue
                        val src = p[0].toInt() and 0xFF
                        val dst = p[1].toInt() and 0xFF
                        val r = p.copyOfRange(2, p.size)
                        if (src != module || dst != Hsfz.TESTER_ADDRESS) {
                            log("← ignored reply from 0x%02X to 0x%02X (expected 0x%02X)".format(src, dst, module)); continue
                        }
                        val ms = clock() - t0
                        val r0 = r[0].toInt() and 0xFF
                        when {
                            r0 == Uds.NEGATIVE_RESPONSE && r.size >= 3 && (r[1].toInt() and 0xFF) == sid -> {
                                val nrc = r[2].toInt() and 0xFF
                                if (nrc == Uds.NRC_RESPONSE_PENDING) {
                                    pending++
                                    deadline = clock() + timing.pendingMillis
                                    log("← 0x%02X response pending (%d)".format(module, pending))
                                } else {
                                    log("← 0x%02X [%s] %s in %d ms".format(module, r.hexString(), Uds.nrcName(nrc), ms))
                                    return UdsOutcome.Negative(nrc, r, ms)
                                }
                            }
                            r0 == sid + Uds.POSITIVE_OFFSET && (sub == null || (r.size >= 2 && (r[1].toInt() and 0xFF) == sub)) -> {
                                log("← 0x%02X [%s] in %d ms".format(module, PacketPreview.of(r), ms))
                                return UdsOutcome.Positive(r, ms)
                            }
                            else -> log("← ignored mismatched reply [%s] from 0x%02X".format(PacketPreview.of(r), module))
                        }
                    }
                    else -> log("← ignored frame with control word 0x%04X".format(f.control))
                }
            }
        }
    }

    fun close() = tcp.close()

    private fun broken(outcome: UdsOutcome): UdsOutcome {
        brokenReason = "earlier request on this connection ended with: ${outcome.summary()}"
        log("✕ 0x%02X %s; no further requests on this connection".format(module, outcome.summary()))
        return outcome
    }
}

internal object PacketPreview {
    fun of(b: ByteArray, max: Int = 64) = if (b.size <= max) b.hexString() else b.copyOf(max).hexString() + " … (${b.size} bytes)"
}
