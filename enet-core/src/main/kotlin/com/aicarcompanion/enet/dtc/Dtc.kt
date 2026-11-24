package com.aicarcompanion.enet.dtc

/** BMW fault codes are shown as the raw three-byte DTC in six hex digits. Never mapped to P-codes. */
object Dtc {
    fun format(dtc: Int): String = "%06X".format(dtc and 0xFFFFFF)
    fun bytes(dtc: Int) = byteArrayOf((dtc ushr 16).toByte(), (dtc ushr 8).toByte(), dtc.toByte())
    internal fun read(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xFF) shl 16) or ((b[at + 1].toInt() and 0xFF) shl 8) or (b[at + 2].toInt() and 0xFF)
}

/** ISO 14229-1 Annex D DTC status byte. */
@JvmInline
value class DtcStatus(val raw: Int) {
    val testFailed get() = bit(0)
    val testFailedThisCycle get() = bit(1)
    val pending get() = bit(2)
    val confirmed get() = bit(3)
    val testNotCompletedSinceClear get() = bit(4)
    val testFailedSinceClear get() = bit(5)
    val testNotCompletedThisCycle get() = bit(6)
    val warningIndicator get() = bit(7)

    /** Active: failing right now. Stored: confirmed in fault memory. Pending: failed, not yet confirmed. */
    val labels: List<String>
        get() = buildList {
            if (testFailed) add("Active")
            if (confirmed) add("Stored")
            if (pending) add("Pending")
            if (warningIndicator) add("Warning lamp requested")
            if (isEmpty()) add("Not active, stored or pending")
        }

    fun describe() = "${labels.joinToString(", ")} (status 0x%02X)".format(raw)

    private fun bit(n: Int) = (raw shr n) and 1 == 1
}

data class DtcRecord(val code: Int, val status: DtcStatus) {
    val hex get() = Dtc.format(code)
}

data class DtcCount(val availabilityMask: Int, val format: Int, val count: Int) {
    val formatName get() = when (format) {
        0x00 -> "ISO 15031-6"
        0x01 -> "ISO 14229-1"
        0x02 -> "SAE J1939-73"
        0x03 -> "ISO 11992-4"
        0x04 -> "SAE J2012-DA"
        else -> "unknown"
    }
}

data class SnapshotId(val code: Int, val record: Int)

/** A parsed list, or the reason it couldn't be parsed. The raw response is always kept by the caller. */
sealed interface Parsed<out T> {
    data class Ok<T>(val value: T) : Parsed<T>
    data class Malformed(val reason: String) : Parsed<Nothing>
}

/** Parsers for positive ReadDTCInformation responses (the full UDS bytes, starting with 0x59). */
object DtcParser {
    private const val RESPONSE_SID = 0x59

    fun count(uds: ByteArray): Parsed<DtcCount> {
        if (!header(uds, 0x01) || uds.size != 6) return Parsed.Malformed("Expected 59 01 + 4 bytes, got ${uds.size} bytes")
        return Parsed.Ok(DtcCount(u(uds, 2), u(uds, 3), (u(uds, 4) shl 8) or u(uds, 5)))
    }

    /** 19 02 and 19 15 responses: availability mask then 4-byte (DTC, status) records. */
    fun list(uds: ByteArray, subFunction: Int = 0x02): Parsed<List<DtcRecord>> {
        if (!header(uds, subFunction) || uds.size < 3) return Parsed.Malformed("Not a 59 %02X response".format(subFunction))
        val body = uds.size - 3
        if (body % 4 != 0) return Parsed.Malformed("Record data is $body bytes, not a multiple of 4")
        return Parsed.Ok((3 until uds.size step 4).map { DtcRecord(Dtc.read(uds, it), DtcStatus(u(uds, it + 3))) })
    }

    /** 19 03 response: 4-byte (DTC, snapshot record number) pairs. */
    fun snapshotIds(uds: ByteArray): Parsed<List<SnapshotId>> {
        if (!header(uds, 0x03)) return Parsed.Malformed("Not a 59 03 response")
        val body = uds.size - 2
        if (body % 4 != 0) return Parsed.Malformed("Record data is $body bytes, not a multiple of 4")
        return Parsed.Ok((2 until uds.size step 4).map { SnapshotId(Dtc.read(uds, it), u(uds, it + 3)) })
    }

    /**
     * 19 04 / 19 06 responses start with the echoed DTC and its status; the record data after
     * that is ECU-specific and kept raw, because decoding it needs BMW data we don't have.
     */
    fun detailHeader(uds: ByteArray, subFunction: Int, expectedDtc: Int): Parsed<DtcRecord> {
        if (!header(uds, subFunction) || uds.size < 6) return Parsed.Malformed("Not a 59 %02X response".format(subFunction))
        val rec = DtcRecord(Dtc.read(uds, 2), DtcStatus(u(uds, 5)))
        if (rec.code != expectedDtc) return Parsed.Malformed("Response is for ${rec.hex}, requested ${Dtc.format(expectedDtc)}")
        return Parsed.Ok(rec)
    }

    private fun header(uds: ByteArray, sub: Int) = uds.size >= 2 && u(uds, 0) == RESPONSE_SID && u(uds, 1) == sub
    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
}

/**
 * Plain-language fault descriptions. Only verified sources may supply text; with none
 * installed every lookup returns null and the UI shows the raw code.
 */
fun interface FaultDescriptions {
    fun describe(moduleAddress: Int, dtc: Int): String?

    companion object {
        val NONE = FaultDescriptions { _, _ -> null }
    }
}
