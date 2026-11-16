package com.aicarcompanion.enet

/**
 * Minimal ISO 13400-2 (DoIP) support: the vehicle identification request and announcement
 * only. 2011 F-series cars use HSFZ, not DoIP, so "no DoIP answer" is the expected result on an
 * early F10; this probe exists so later G-series cars can be recognized without guessing.
 */
object Doip {
    const val PORT = 13400
    const val TYPE_VEHICLE_ID_REQUEST = 0x0001
    const val TYPE_VEHICLE_ANNOUNCEMENT = 0x0004
    const val HEADER_SIZE = 8

    /** ISO 13400-2:2012 protocol version 0x02 (inverse 0xFD). */
    const val PROTOCOL_VERSION = 0x02

    val VEHICLE_ID_REQUEST: ByteArray = header(TYPE_VEHICLE_ID_REQUEST, 0)

    fun header(type: Int, payloadLength: Int) = byteArrayOf(
        PROTOCOL_VERSION.toByte(), (PROTOCOL_VERSION.inv() and 0xFF).toByte(),
        (type ushr 8).toByte(), type.toByte(),
        (payloadLength ushr 24).toByte(), (payloadLength ushr 16).toByte(), (payloadLength ushr 8).toByte(), payloadLength.toByte(),
    )
}

data class DoipAnnouncement(val vin: String, val logicalAddress: Int, val eid: String, val gid: String)

object DoipParser {
    private const val MIN_PAYLOAD = 17 + 2 + 6 + 6 + 1

    /** Parses a vehicle announcement / identification response. Null for anything else. */
    fun parseAnnouncement(datagram: ByteArray): DoipAnnouncement? {
        if (datagram.size < Doip.HEADER_SIZE + MIN_PAYLOAD) return null
        val version = datagram[0].toInt() and 0xFF
        if (version !in 0x01..0x04 && version != 0xFF) return null
        if ((datagram[1].toInt() and 0xFF) != (version.inv() and 0xFF)) return null
        val type = ((datagram[2].toInt() and 0xFF) shl 8) or (datagram[3].toInt() and 0xFF)
        if (type != Doip.TYPE_VEHICLE_ANNOUNCEMENT) return null
        val len = ((datagram[4].toLong() and 0xFF) shl 24) or ((datagram[5].toLong() and 0xFF) shl 16) or
            ((datagram[6].toLong() and 0xFF) shl 8) or (datagram[7].toLong() and 0xFF)
        if (len < MIN_PAYLOAD || Doip.HEADER_SIZE + len > datagram.size) return null
        val p = Doip.HEADER_SIZE
        val vin = String(datagram, p, 17, Charsets.ISO_8859_1)
        val addr = ((datagram[p + 17].toInt() and 0xFF) shl 8) or (datagram[p + 18].toInt() and 0xFF)
        fun hex(from: Int) = (from until from + 6).joinToString("") { "%02X".format(datagram[it].toInt() and 0xFF) }
        return DoipAnnouncement(vin, addr, hex(p + 19), hex(p + 25))
    }
}
