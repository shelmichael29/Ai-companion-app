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
enum class F10Module(val address: Int, val title: String, val equipment: String) {
    DME(0x12, "Engine computer", "Standard"),
    EGS(0x18, "Transmission", "Automatic transmission"),
    DSC(0x29, "Stability and braking", "Standard"),
    KOMBI(0x60, "Instrument cluster", "Standard"),
    JBE(0x00, "Junction box electronics", "Standard; parking sensors may be integrated"),
    ACSM(0x01, "Airbag and crash safety", "Standard"),
    SZL(0x02, "Steering column switches", "Equipment dependent"),
    TRSVC(0x06, "Surround and rear cameras", "Camera option"),
    SWW(0x08, "Blind spot warning", "Lane change warning option"),
    ZGW(0x10, "Central gateway", "Standard"),
    IAS(0x16, "Active steering actuator", "Integral active steering option"),
    EKPS(0x17, "Fuel pump controller", "Equipment dependent"),
    VTG(0x19, "All wheel drive transfer case", "xDrive only"),
    ICM(0x1C, "Integrated chassis management", "Standard"),
    RDC(0x20, "Tire pressure monitoring", "Market and equipment dependent"),
    ACC(0x21, "Adaptive cruise radar", "Active cruise option"),
    RSE(0x26, "Rear seat entertainment", "Rear entertainment option"),
    EMF(0x2A, "Electronic parking brake", "Standard"),
    HSR(0x2B, "Rear wheel steering", "Integral active steering option"),
    PMA(0x2C, "Parking maneuver assistant", "Parking assistant option"),
    EPS(0x30, "Electric power steering", "Steering system dependent"),
    COMBOX(0x36, "Phone and media interface", "Combox option"),
    AMP(0x37, "Audio amplifier", "Diagnostic capable amplifier option"),
    VDM(0x39, "Vertical dynamics management", "Adaptive chassis option"),
    HUD(0x3D, "Head up display", "Head up display option"),
    CAS(0x40, "Car access and start authorization", "Standard"),
    FZD(0x56, "Roof electronics", "Equipment dependent"),
    NVE(0x57, "Night vision", "Night vision option"),
    KAFAS(0x5D, "Driver assistance camera", "Driver assistance option"),
    GWS(0x5E, "Gear selector", "Automatic transmission"),
    FLA(0x5F, "High beam assistant", "High beam assistant option"),
    ECALL(0x61, "Emergency call interface", "Combox emergency call option"),
    HU(0x63, "Head unit (CHAMP2 or CIC)", "Standard; shared address"),
    PDC(0x64, "Parking distance controller", "Separate parking controller if fitted"),
    ZBE(0x67, "iDrive controller", "Equipment dependent"),
    HKL(0x6B, "Power trunk controller", "Power trunk option"),
    SMFA(0x6D, "Driver seat memory", "Seat memory option"),
    SMBF(0x6E, "Passenger seat memory", "Seat memory option"),
    AHM(0x71, "Trailer controller", "Trailer option"),
    FRM(0x72, "Footwell and lighting electronics", "Standard"),
    CID(0x73, "Central information display", "Equipment dependent"),
    IHKA(0x78, "Heating and air conditioning", "Standard"),
    FKA(0x79, "Rear climate control", "Four zone climate option"),
    EDC_FL(0xA5, "Front left damper controller", "Electronic damper option"),
    EDC_FR(0xA6, "Front right damper controller", "Electronic damper option"),
    EDC_RL(0xA7, "Rear left damper controller", "Electronic damper option"),
    EDC_RR(0xA8, "Rear right damper controller", "Electronic damper option"),
}

sealed interface ModuleReply {
    data class Positive(val vin: String? = null) : ModuleReply
    data class Rejected(val code: Int) : ModuleReply
    data class GatewayError(val control: Int) : ModuleReply
    /** The physical TCP link failed for this module only; continue the remainder of the scan. */
    data class TransportError(val reason: String) : ModuleReply
    data object NoResponse : ModuleReply
    data object InvalidVin : ModuleReply
    data object Pending : ModuleReply
}

class ModuleDiagnostics(private val transport: EnetTransport, private val clock: () -> Long) {
    suspend fun run(
        link: EnetLink, target: Ipv4Address, connectTimeout: Long, readVin: Boolean,
        progress: (F10Module) -> Unit, result: (F10Module, ModuleReply) -> Unit,
        selectedModules: List<F10Module> = F10Module.entries,
    ) {
        // Freeze and deduplicate the selection before opening any connection.
        val modules = if (readVin) listOf(F10Module.DME) else selectedModules.toList().distinct()
        require(modules.isNotEmpty()) { "Select at least one module to scan" }
        for (verdict in listOf(TargetPolicy.checkLocal(link.subnet), TargetPolicy.checkTarget(target, link.subnet))) {
            if (verdict is TargetPolicy.Verdict.Rejected) throw IOException(verdict.reason)
        }
        val request = if (readVin) SafeRequest.READ_VIN else SafeRequest.TESTER_PRESENT
        for (module in modules) {
            currentCoroutineContext().ensureActive()
            progress(module)
            // Gateway routing errors may omit the ECU address. A fresh stream prevents a late
            // error for a previous module from being attributed to the current one.
            try {
                transport.connectTcp(link.subnet.address, target, Hsfz.DIAG_PORT,
                    connectTimeout.coerceIn(ConnectionOptions.CONNECT_TIMEOUT_RANGE)).use { tcp ->
                    tcp.write(Hsfz.diagnosticFrame(request, module.address))
                    result(module, receive(tcp, HsfzFrameReader(), module, request))
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled // The user hit Cancel; never continue sending to another ECU.
            } catch (e: IOException) {
                // A refused connection, cable hiccup, or broken stream is not evidence that the
                // ECU is absent. Record this candidate and continue with the next one.
                result(module, ModuleReply.TransportError(e.message?.take(120) ?: e.javaClass.simpleName))
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
