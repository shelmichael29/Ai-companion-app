package com.aicarcompanion.enet

import kotlinx.coroutines.CancellationException
import java.io.IOException

/*
 * Read-only diagnostic exchanges with one module behind the gateway (engine VIN, module scan).
 *
 * The rule behind all of it: a module counts as having answered only when a diagnostic message
 * arrives FROM that module's own address, addressed to the tester. A gateway acknowledgment
 * (it only says "message forwarded"), an HSFZ error, or a reply from some other address is never
 * evidence from the module, and silence is never proof that a module is absent.
 */

/** One control unit on the F-series diagnostic bus. */
data class EcuModule(
    val address: Int,
    /** BMW abbreviation. */
    val abbreviation: String,
    /** Plain-English name. */
    val name: String,
    /** Where this address is documented. */
    val source: String,
)

/**
 * The modules the scan checks, and the ones it doesn't. Only addresses found in published sources are
 * listed; each says where. The address table the community documents is for F-series cars in general
 * (one source is for a 2016 model), so a module being absent on a 2011 F10, or fitted at another
 * address, is possible and is not an error.
 */
object F10Modules {
    private const val GITHUB_TABLE = "community F-series ECU table (packetpilot/bmw-f, from a 2016 car)"
    private const val QUANTEX = "Quantex ELM327 HSFZ documentation"

    val ZGW = EcuModule(0x10, "ZGW", "Central gateway", "$QUANTEX (typically the ZGW); EdiabasLib")
    val DME = EcuModule(0x12, "DME", "Engine computer (Digital Motor Electronics)", "$GITHUB_TABLE; $QUANTEX (engine ECU example)")
    val DSC = EcuModule(0x29, "DSC", "Stability control and ABS (Dynamic Stability Control)", GITHUB_TABLE)
    val EPS = EcuModule(0x30, "EPS", "Electric power steering (if fitted)", GITHUB_TABLE)
    val CAS = EcuModule(0x40, "CAS", "Car access and body electronics (CAS on a 2011 F10; FEM on later cars)", GITHUB_TABLE)
    val KOMBI = EcuModule(0x60, "KOMBI", "Instrument cluster", GITHUB_TABLE)
    val IHKA = EcuModule(0x78, "IHKA", "Automatic climate control", GITHUB_TABLE)
    val ACSM = EcuModule(0x01, "ACSM", "Airbag and crash safety module", GITHUB_TABLE)

    /** Checked by the scan, in this order. */
    val SCANNED: List<EcuModule> = listOf(ZGW, DME, DSC, EPS, CAS, KOMBI, IHKA, ACSM)

    /** Not checked because no published address could be confirmed. */
    val NOT_CHECKED: List<String> = listOf(
        "Automatic transmission (EGS): address not confirmed in the sources found",
        "Footwell module (FRM) and junction box: not listed separately",
        "Parking assistance (PDC), steering column (SZL), iDrive/head unit and others: no confirmed address",
    )

    const val SILENCE_NOTE =
        "Silence is not proof that a module is absent: it may be asleep, fitted at another address, or simply not answer this request."

    fun coverage(checked: List<EcuModule> = SCANNED): ScanCoverage = ScanCoverage(checked, NOT_CHECKED)
}

data class ScanCoverage(val checked: List<EcuModule>, val notChecked: List<String>) {
    /** One sentence saying which modules were checked, for the results screen and the log. */
    val statement: String
        get() = "Checked ${checked.size} modules: ${checked.joinToString { it.abbreviation }}. Not checked: ${notChecked.joinToString("; ")}. ${F10Modules.SILENCE_NOTE}"
}

/** What came back from one module. Nothing here is built from a gateway acknowledgment. */
internal sealed interface ModuleReply {
    /** A positive response from the module's own address. */
    class Positive(val source: Int, val uds: ByteArray, val millis: Long) : ModuleReply

    /** An explicit negative response from the module's own address: it answered, and declined. */
    class Rejected(val source: Int, val nrc: Int, val millis: Long) : ModuleReply

    /** No diagnostic reply from the module. Says what else was heard, which is not evidence about the module. */
    class Silent(val gatewayAcked: Boolean, val gatewayError: Int?, val otherSources: Set<Int>, val millis: Long) : ModuleReply

    /** The connection failed or ended. */
    class Lost(val reason: String) : ModuleReply
}

/** One open HSFZ connection, used for one request at a time. */
internal class DiagnosticLink(
    private val tcp: TcpChannel,
    private val log: EnetLog,
    private val clock: () -> Long,
) {
    private val reader = HsfzFrameReader()

    /** Sends [request] to [module] and waits up to [timeoutMillis] (extended at most twice if the module says "response pending"). */
    suspend fun exchange(request: SafeRequest, module: Int, timeoutMillis: Long): ModuleReply {
        val frame = Hsfz.diagnosticFrame(request, module)
        val t0 = clock()
        try {
            tcp.write(frame)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return ModuleReply.Lost("Sending failed: ${e.message ?: e.javaClass.simpleName}")
        }
        log.add("→ ${request.label} to module 0x%02X".format(module))
        var acked = false
        val others = linkedSetOf<Int>()
        var deadline = t0 + timeoutMillis
        var extensions = 0
        while (true) {
            val remaining = deadline - clock()
            if (remaining <= 0) break
            val bytes = try {
                tcp.read(1024, remaining)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                return ModuleReply.Lost("Connection dropped: ${e.message ?: e.javaClass.simpleName}")
            } ?: break
            if (bytes.isEmpty()) return ModuleReply.Lost("The gateway closed the connection.")
            val frames = try { reader.feed(bytes) } catch (e: HsfzProtocolException) {
                log.add("Protocol error: ${e.message}")
                return ModuleReply.Lost("Unexpected data: ${e.message}")
            }
            for (f in frames) {
                when (f.control) {
                    Hsfz.CTRL_ALIVE_CHECK -> {
                        log.add("← Alive check from gateway; answering")
                        try { tcp.write(Hsfz.ALIVE_RESPONSE) } catch (e: CancellationException) { throw e } catch (e: IOException) { /* the next read reports it */ }
                    }
                    Hsfz.CTRL_ACK -> { acked = true; log.add("← Gateway acknowledged the message (forwarded; not an answer from the module)") }
                    Hsfz.CTRL_NACK -> {
                        log.add("← Gateway negative acknowledgement")
                        return ModuleReply.Silent(acked, Hsfz.CTRL_NACK, others, clock() - t0)
                    }
                    in 0x0040..0x0045 -> {
                        log.add("← Gateway HSFZ error 0x%04X".format(f.control))
                        return ModuleReply.Silent(acked, f.control, others, clock() - t0)
                    }
                    Hsfz.CTRL_DIAG -> {
                        val p = f.payload
                        if (p.size < 3) continue
                        val src = p[0].toInt() and 0xFF
                        val dst = p[1].toInt() and 0xFF
                        val uds = p.copyOfRange(2, p.size)
                        if (dst != Hsfz.TESTER_ADDRESS) { log.add("← Diagnostic message to 0x%02X ignored (not for this tester)".format(dst)); continue }
                        if (src != module) {
                            others += src
                            log.add("← Reply from 0x%02X ignored: it is not module 0x%02X".format(src, module))
                            continue
                        }
                        val ms = clock() - t0
                        val sid = request.uds[0]
                        if (uds.size >= 3 && uds[0] == 0x7F.toByte() && uds[1] == sid) {
                            val nrc = uds[2].toInt() and 0xFF
                            if (nrc == NRC_RESPONSE_PENDING && extensions < MAX_PENDING_EXTENSIONS) {
                                extensions++
                                deadline = clock() + timeoutMillis
                                log.add("← Module 0x%02X says: working on it (response pending); waiting a little longer".format(module))
                                continue
                            }
                            log.add("← Negative response from 0x%02X: NRC 0x%02X (%s) in %d ms".format(src, nrc, Nrc.describe(nrc), ms))
                            return ModuleReply.Rejected(src, nrc, ms)
                        }
                        val positive = if (request.positiveIsPrefix) uds.startsWith(request.positiveResponse) else uds.contentEquals(request.positiveResponse)
                        if (positive) {
                            log.add("← Positive response from 0x%02X in %d ms: %s".format(src, ms, describePositive(uds)))
                            return ModuleReply.Positive(src, uds, ms)
                        }
                        log.add("← Unrelated message from 0x%02X ignored: %s".format(src, describePositive(uds)))
                    }
                    else -> log.add("← Frame with control word 0x%04X ignored".format(f.control))
                }
            }
        }
        val ms = clock() - t0
        log.add("No diagnostic reply from module 0x%02X within %d ms%s".format(module, timeoutMillis, if (acked) " (the gateway had acknowledged)" else ""))
        return ModuleReply.Silent(acked, null, others, ms)
    }

    private companion object {
        const val NRC_RESPONSE_PENDING = 0x78
        const val MAX_PENDING_EXTENSIONS = 2
    }
}

/** Never shows data bytes of a positive response: a VIN reply must not reach the log, even as hex. */
internal fun describePositive(uds: ByteArray): String =
    if (uds.size <= 3) uds.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    else uds.take(3).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } + " + ${uds.size - 3} data bytes (not shown)"

private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

/** Plain names for the negative response codes a module can answer with. */
object Nrc {
    fun describe(code: Int): String = when (code) {
        0x10 -> "general reject"
        0x11 -> "service not supported"
        0x12 -> "sub-function not supported"
        0x13 -> "incorrect message length"
        0x22 -> "conditions not correct"
        0x31 -> "request out of range"
        0x33 -> "security access denied"
        0x78 -> "response pending"
        0x7E -> "sub-function not supported in this session"
        0x7F -> "service not supported in this session"
        else -> "other reason"
    }
}

/** A VIN is 17 characters from A–Z and 0–9 without I, O and Q. */
object VinFormat {
    private val VALID = Regex("[A-HJ-NPR-Z0-9]{17}")
    fun isValid(vin: String): Boolean = VALID.matches(vin)
}

/** Whether the engine computer's VIN agrees with the one the gateway announced. */
enum class VinMatch { MATCHES_GATEWAY, DIFFERS_FROM_GATEWAY, NO_GATEWAY_VIN }

/** The gateway's VIN as discovery reported it: masked for display, fingerprinted for comparison. */
data class GatewayVinReference(val masked: String, val digest: String)

fun DiscoveredGateway.vinReference(): GatewayVinReference? =
    if (maskedVin != null && vinDigest != null) GatewayVinReference(maskedVin, vinDigest) else null

sealed interface EngineVinResult {
    /** The DME itself answered with a well-formed VIN. [vin] is for the screen only; [toString] masks it. */
    data class Success(
        val module: EcuModule,
        val vin: String,
        val match: VinMatch,
        val gatewayMaskedVin: String?,
        val latencyMillis: Long,
    ) : EngineVinResult {
        override fun toString() = "Success(module=${module.abbreviation}, vin=${Redaction.maskVin(vin)}, match=$match)"
    }

    /** The DME answered but declined: it is present, and no VIN was read. */
    data class Rejected(val module: EcuModule, val nrc: Int, val detail: String) : EngineVinResult

    /** A reply came from the DME, but it isn't a usable VIN. Nothing is shown as the VIN. */
    data class Unusable(val module: EcuModule, val detail: String) : EngineVinResult

    /** The DME did not answer (a gateway acknowledgment or another module's reply doesn't count). */
    data class NoAnswer(val detail: String) : EngineVinResult

    data class ConnectionFailed(val detail: String) : EngineVinResult
    data class Refused(val reason: String) : EngineVinResult
}

enum class ScanOutcome(val label: String) {
    /** A positive reply from the module. */
    POSITIVE("Replied"),

    /** An explicit diagnostic rejection from the module: it answered, so it is there. */
    REJECTED("Replied: declined the request"),

    /** Nothing from the module. Not proof it is absent. */
    NO_RESPONSE("No response"),

    /** The scan stopped before this module. */
    NOT_CHECKED("Not checked"),
    ;

    /** True when the module itself answered, positively or with a rejection. */
    val answered: Boolean get() = this == POSITIVE || this == REJECTED
}

data class ModuleScanEntry(
    val module: EcuModule,
    val outcome: ScanOutcome,
    val detail: String,
    val nrc: Int? = null,
    val latencyMillis: Long? = null,
)

enum class ScanEnd { COMPLETE, CONNECTION_LOST }

/** Reported while a scan runs: what is done, and which module is being asked now. */
data class ScanProgress(val entries: List<ModuleScanEntry>, val total: Int, val current: EcuModule?)

data class ScanOptions(
    val modules: List<EcuModule> = F10Modules.SCANNED,
    val connectTimeoutMillis: Long = ConnectionOptions.DEFAULT_CONNECT_TIMEOUT_MILLIS,
    /** How long to wait for each module. Clamped to [MODULE_TIMEOUT_RANGE]. */
    val moduleTimeoutMillis: Long = DEFAULT_MODULE_TIMEOUT_MILLIS,
) {
    companion object {
        const val DEFAULT_MODULE_TIMEOUT_MILLIS = 1_500L
        val MODULE_TIMEOUT_RANGE = 500L..3_000L
    }
}

sealed interface ModuleScanResult {
    data class Finished(
        val entries: List<ModuleScanEntry>,
        val end: ScanEnd,
        val endDetail: String?,
        val coverage: ScanCoverage,
    ) : ModuleScanResult {
        val answered: Int get() = entries.count { it.outcome.answered }
    }

    data class Refused(val reason: String) : ModuleScanResult
    data class ConnectionFailed(val detail: String) : ModuleScanResult
}
