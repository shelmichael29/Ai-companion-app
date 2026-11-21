package com.aicarcompanion.enet

import java.io.EOFException
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Discovery retains a comparison value only. Its string representation never contains a VIN. */
class VinIdentity(vin: String) {
    private val digest = hash(vin)
    fun matches(vin: String) = digest.contentEquals(hash(vin))
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII))
}

/** Fixed physical diagnostic addresses for this F10 coverage set. Equipment varies by vehicle. */
enum class F10Module(val address: Int, val title: String) {
    DME(0x12, "Engine computer"),
    EGS(0x18, "Transmission"),
    DSC(0x29, "Stability and braking"),
    KOMBI(0x60, "Instrument cluster"),
}

sealed interface ModuleReply {
    data class Positive(val vin: String? = null) : ModuleReply
    data class Rejected(val code: Int) : ModuleReply
    data class GatewayError(val control: Int) : ModuleReply
    data object NoResponse : ModuleReply
    data object InvalidVin : ModuleReply
    data object Pending : ModuleReply
}

class ModuleDiagnostics(private val transport: EnetTransport, private val clock: () -> Long) {
    suspend fun run(
        link: EnetLink, target: Ipv4Address, connectTimeout: Long, readVin: Boolean,
        progress: (F10Module) -> Unit, result: (F10Module, ModuleReply) -> Unit,
    ) {
        for (verdict in listOf(TargetPolicy.checkLocal(link.subnet), TargetPolicy.checkTarget(target, link.subnet))) {
            if (verdict is TargetPolicy.Verdict.Rejected) throw IOException(verdict.reason)
        }
        val modules = if (readVin) listOf(F10Module.DME) else F10Module.entries
        val request = if (readVin) SafeRequest.READ_VIN else SafeRequest.TESTER_PRESENT
        for (module in modules) {
            currentCoroutineContext().ensureActive()
            progress(module)
            // Gateway routing errors may omit the ECU address. A fresh stream prevents a late
            // error for a previous module from being attributed to the current one.
            transport.connectTcp(link.subnet.address, target, Hsfz.DIAG_PORT,
                connectTimeout.coerceIn(ConnectionOptions.CONNECT_TIMEOUT_RANGE)).use { tcp ->
                tcp.write(Hsfz.diagnosticFrame(request, module.address))
                result(module, receive(tcp, HsfzFrameReader(), module, request))
            }
        }
    }

    private suspend fun receive(tcp: TcpChannel, reader: HsfzFrameReader, module: F10Module, request: SafeRequest): ModuleReply {
        val deadline = clock() + 3000
        var timeout: ModuleReply = ModuleReply.NoResponse
        while (true) {
            currentCoroutineContext().ensureActive()
            val remaining = deadline - clock()
            if (remaining <= 0) return timeout
            val bytes = tcp.read(1024, remaining) ?: return timeout
            if (bytes.isEmpty()) throw EOFException("Gateway closed the connection")
            for (frame in reader.feed(bytes)) {
                when (frame.control) {
                    Hsfz.CTRL_ALIVE_CHECK -> tcp.write(Hsfz.ALIVE_RESPONSE)
                    Hsfz.CTRL_NACK, in 0x40..0x45 -> return ModuleReply.GatewayError(frame.control)
                    Hsfz.CTRL_DIAG -> {
                        val p = frame.payload
                        if (p.size < 3 || p[0] != module.address.toByte() || p[1] != Hsfz.TESTER_ADDRESS.toByte()) continue
                        val uds = p.copyOfRange(2, p.size)
                        if (uds.size == 3 && uds[0] == 0x7F.toByte() && uds[1] == request.uds[0]) {
                            val code = uds[2].toInt() and 0xFF
                            if (code != 0x78) return ModuleReply.Rejected(code)
                            timeout = ModuleReply.Pending
                        }
                        if (request == SafeRequest.TESTER_PRESENT && uds.contentEquals(request.positiveResponse)) return ModuleReply.Positive()
                        if (request == SafeRequest.READ_VIN && uds.size >= 3 && uds.copyOfRange(0, 3).contentEquals(request.positiveResponse)) {
                            val vin = String(uds, 3, uds.size - 3, Charsets.US_ASCII)
                            return if (VIN.matches(vin)) ModuleReply.Positive(vin) else ModuleReply.InvalidVin
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val VIN = Regex("[A-HJ-NPR-Z0-9]{17}")
    }
}
