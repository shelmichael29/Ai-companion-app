package com.aicarcompanion.enet

/**
 * BMW "HSFZ" (High-Speed Fahrzeug-Zugang) framing used by ENET on F-series cars.
 *
 * Sources: the open-source EdiabasLib implementation (EdInterfaceEnet.cs, uholeschak/ediabaslib):
 *  - UDP vehicle identification on port 6811: request `00 00 00 00 00 11`, response payload
 *    `DIAGADR<nn>BMWMAC<12 hex>BMWVIN<17 chars>`.
 *  - TCP diagnostics on port 6801: 4-byte big-endian length (bytes after the 6-byte header),
 *    2-byte control word, then for diagnostic messages: source address, target address, UDS data.
 *  - Control words: 0x0001 diagnostic message, 0x0002 acknowledgement (echoes the request),
 *    0x00FF negative acknowledgement, 0x0010 ignition (clamp) status on the control port,
 *    0x0011 vehicle identification, 0x0012 alive check, 0x0013 alive-check response.
 * These are community-documented, not official BMW specifications; verify on the car.
 */
object Hsfz {
    const val IDENT_PORT = 6811
    const val CONTROL_PORT = 6811
    const val DIAG_PORT = 6801

    const val CTRL_DIAG = 0x0001
    const val CTRL_ACK = 0x0002
    const val CTRL_IGNITION = 0x0010
    const val CTRL_IDENT = 0x0011
    const val CTRL_ALIVE_CHECK = 0x0012
    const val CTRL_ALIVE_RESPONSE = 0x0013
    const val CTRL_NACK = 0x00FF

    /** Default tester address used by BMW tools. */
    const val TESTER_ADDRESS = 0xF4

    /** Central gateway (ZGW) diagnostic address on F-series. */
    const val GATEWAY_ADDRESS = 0x10

    const val HEADER_SIZE = 6

    /** Largest frame this tester will accept; bounds memory use on malformed input. */
    const val MAX_PAYLOAD = 4096

    val IDENT_REQUEST: ByteArray = frame(CTRL_IDENT, ByteArray(0))
    val IGNITION_REQUEST: ByteArray = frame(CTRL_IGNITION, ByteArray(0))
    val ALIVE_RESPONSE: ByteArray = frame(CTRL_ALIVE_RESPONSE, byteArrayOf(0x00, TESTER_ADDRESS.toByte()))

    fun frame(control: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER_SIZE + payload.size)
        val len = payload.size
        out[0] = (len ushr 24).toByte(); out[1] = (len ushr 16).toByte(); out[2] = (len ushr 8).toByte(); out[3] = len.toByte()
        out[4] = (control ushr 8).toByte(); out[5] = control.toByte()
        payload.copyInto(out, HEADER_SIZE)
        return out
    }

    /** A diagnostic message carrying one of the fixed, read-only [SafeRequest]s. Nothing else can be built. */
    fun diagnosticFrame(request: SafeRequest, target: Int = GATEWAY_ADDRESS): ByteArray =
        frame(CTRL_DIAG, byteArrayOf(TESTER_ADDRESS.toByte(), target.toByte()) + request.uds)
}

/**
 * The only diagnostic requests this tester can send. Fixed bytes, no user input, and none of
 * them changes anything in the car: no coding, programming, actuator tests or code clearing.
 */
enum class SafeRequest(val label: String, val uds: ByteArray, val positiveResponse: ByteArray) {
    /** UDS TesterPresent (0x3E, sub-function 0x00): "are you there?", requires a response. */
    TESTER_PRESENT("TesterPresent (3E 00)", byteArrayOf(0x3E, 0x00), byteArrayOf(0x7E, 0x00)),
}

data class HsfzFrame(val control: Int, val payload: ByteArray) {
    override fun equals(other: Any?) = other is HsfzFrame && other.control == control && other.payload.contentEquals(payload)
    override fun hashCode() = 31 * control + payload.contentHashCode()
}

class HsfzProtocolException(message: String) : Exception(message)

/** Reassembles frames from a TCP byte stream, whatever way the bytes are split. */
class HsfzFrameReader(private val maxPayload: Int = Hsfz.MAX_PAYLOAD) {
    private var buffer = ByteArray(0)

    /** Adds received bytes and returns every complete frame. @throws HsfzProtocolException on an oversized frame. */
    fun feed(bytes: ByteArray): List<HsfzFrame> {
        buffer += bytes
        val frames = mutableListOf<HsfzFrame>()
        while (buffer.size >= Hsfz.HEADER_SIZE) {
            val len = ((buffer[0].toLong() and 0xFF) shl 24) or ((buffer[1].toLong() and 0xFF) shl 16) or
                ((buffer[2].toLong() and 0xFF) shl 8) or (buffer[3].toLong() and 0xFF)
            if (len > maxPayload) throw HsfzProtocolException("Frame announces $len bytes (limit $maxPayload); stopping.")
            val total = Hsfz.HEADER_SIZE + len.toInt()
            if (buffer.size < total) break
            val control = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
            frames += HsfzFrame(control, buffer.copyOfRange(Hsfz.HEADER_SIZE, total))
            buffer = buffer.copyOfRange(total, buffer.size)
        }
        return frames
    }
}

/** What a gateway reported in its HSFZ identification answer. */
data class HsfzIdentity(val diagAddress: String?, val mac: String?, val vin: String?)

object HsfzIdentParser {
    private val DIAGADR = Regex("DIAGADR([0-9]{1,3})")
    private val MAC = Regex("BMWMAC([0-9A-Fa-f]{12})")
    private val VIN = Regex("BMWVIN([A-HJ-NPR-Z0-9]{17})")

    /** Parses a UDP datagram from port 6811. Null when it isn't an HSFZ identification answer. */
    fun parse(datagram: ByteArray): HsfzIdentity? {
        if (datagram.size < Hsfz.HEADER_SIZE + 9) return null
        // EdiabasLib does not check the reply's control word, only the "DIAGADR" text at offset 6,
        // so neither do we; the strict text match below rejects anything else.
        if (!String(datagram, Hsfz.HEADER_SIZE, 7, Charsets.ISO_8859_1).startsWith("DIAGADR")) return null
        val text = String(datagram, Hsfz.HEADER_SIZE, datagram.size - Hsfz.HEADER_SIZE, Charsets.ISO_8859_1)
        val diag = DIAGADR.find(text)?.groupValues?.get(1) ?: return null
        return HsfzIdentity(diag, MAC.find(text)?.groupValues?.get(1)?.uppercase(), VIN.find(text)?.groupValues?.get(1))
    }
}
