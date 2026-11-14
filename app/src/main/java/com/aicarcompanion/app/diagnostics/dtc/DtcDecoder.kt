package com.aicarcompanion.app.diagnostics.dtc

/** Converts raw OBD-II DTC bytes into the standard five-character form (SAE J2012). */
object DtcDecoder {

    private val SYSTEM_LETTERS = charArrayOf('P', 'C', 'B', 'U')

    /** Two raw bytes -> e.g. "P0133". Returns null for the 0x0000 padding value. */
    fun decode(high: Int, low: Int): String? {
        require(high in 0..0xFF && low in 0..0xFF) { "Bytes must be 0..255" }
        if (high == 0 && low == 0) return null
        val letter = SYSTEM_LETTERS[(high shr 6) and 0x3]
        val secondDigit = (high shr 4) and 0x3
        val thirdDigit = high and 0xF
        return "%c%d%X%02X".format(letter, secondDigit, thirdDigit, low)
    }

    /** Decodes consecutive byte pairs, skipping padding. */
    fun decodePayload(bytes: IntArray): List<String> =
        (0 until bytes.size / 2).mapNotNull { i -> decode(bytes[i * 2], bytes[i * 2 + 1]) }
}
