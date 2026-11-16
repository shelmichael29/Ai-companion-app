package com.aicarcompanion.enet

/**
 * Describes received packets for the diagnostic log without leaking vehicle identifiers:
 * identification answers carry the VIN (and MAC/EID), so they are shown as header bytes plus
 * the masked ASCII payload instead of a raw hex dump.
 */
object PacketFormat {
    private const val MAX_HEX = 48

    fun hex(bytes: ByteArray, max: Int = Int.MAX_VALUE): String {
        val shown = bytes.take(max).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        return if (bytes.size > max) "$shown …" else shown
    }

    fun describe(fromPort: Int, data: ByteArray): String = when {
        fromPort == Hsfz.IDENT_PORT && HsfzIdentParser.parse(data) != null -> {
            val text = String(data, Hsfz.HEADER_SIZE, data.size - Hsfz.HEADER_SIZE, Charsets.ISO_8859_1)
            "${data.size} bytes, header [${hex(data.copyOf(Hsfz.HEADER_SIZE))}] payload \"${Redaction.redact(text)}\""
        }
        fromPort == Doip.PORT && DoipParser.parseAnnouncement(data) != null ->
            "${data.size} bytes, header [${hex(data.copyOf(Doip.HEADER_SIZE))}] vehicle announcement (VIN/EID not shown)"
        else -> "${data.size} bytes [${hex(data, MAX_HEX)}]"
    }
}
