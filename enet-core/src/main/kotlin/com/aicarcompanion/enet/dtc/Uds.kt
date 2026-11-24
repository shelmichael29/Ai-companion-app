package com.aicarcompanion.enet.dtc

/**
 * UDS (ISO 14229-1) request and response helpers for BMW F-series fault memory over HSFZ.
 *
 * Request layouts are the ISO 14229-1 standard ones; nothing here is BMW-proprietary:
 *  - 0x19 ReadDTCInformation, sub-functions 0x01, 0x02, 0x03, 0x04, 0x06, 0x15.
 *  - 0x14 ClearDiagnosticInformation with groupOfDTC 0xFFFFFF ("all groups").
 *  - 0x3E TesterPresent, sub-function 0x00.
 * BMW support for each sub-function varies by ECU; unsupported requests are reported, not guessed.
 */
object Uds {
    const val SID_READ_DTC = 0x19
    const val SID_CLEAR_DTC = 0x14
    const val SID_TESTER_PRESENT = 0x3E
    const val NEGATIVE_RESPONSE = 0x7F
    const val POSITIVE_OFFSET = 0x40

    const val NRC_RESPONSE_PENDING = 0x78

    /** Service IDs that can never change the vehicle. Anything else is refused by [ReadRequest]. */
    val READ_ONLY_SERVICES = setOf(SID_READ_DTC, SID_TESTER_PRESENT)

    /** ISO 14229-1 Annex A negative response code names. */
    fun nrcName(code: Int): String = when (code) {
        0x10 -> "generalReject"
        0x11 -> "serviceNotSupported"
        0x12 -> "subFunctionNotSupported"
        0x13 -> "incorrectMessageLengthOrInvalidFormat"
        0x14 -> "responseTooLong"
        0x21 -> "busyRepeatRequest"
        0x22 -> "conditionsNotCorrect"
        0x24 -> "requestSequenceError"
        0x25 -> "noResponseFromSubnetComponent"
        0x26 -> "failurePreventsExecutionOfRequestedAction"
        0x31 -> "requestOutOfRange"
        0x33 -> "securityAccessDenied"
        0x72 -> "generalProgrammingFailure"
        0x78 -> "requestCorrectlyReceived-ResponsePending"
        0x7E -> "subFunctionNotSupportedInActiveSession"
        0x7F -> "serviceNotSupportedInActiveSession"
        else -> "unknown NRC"
    }

    /** NRCs meaning "this ECU doesn't offer this request" rather than "refused right now". */
    fun isUnsupported(code: Int) = code in setOf(0x11, 0x12, 0x7E, 0x7F)
}

/**
 * A read-only diagnostic request. Only the factory functions below can create one, and the
 * constructor re-checks the service ID, so a ClearDiagnosticInformation or any other
 * vehicle-modifying service can't be smuggled through this type.
 */
class ReadRequest private constructor(val label: String, uds: ByteArray) {
    private val bytes = uds.copyOf()
    val uds: ByteArray get() = bytes.copyOf()
    val sid: Int get() = bytes[0].toInt() and 0xFF
    /** Sub-function echoed in the positive response, or null for services without one. */
    val subFunction: Int? get() = if (sid == Uds.SID_READ_DTC) bytes[1].toInt() and 0xFF else null

    init {
        require(bytes.isNotEmpty() && sid in Uds.READ_ONLY_SERVICES) { "Not a read-only service" }
    }

    override fun toString() = "$label [${bytes.hexString()}]"

    companion object {
        fun testerPresent() = ReadRequest("TesterPresent", byteArrayOf(0x3E, 0x00))

        /** 19 01 FF: number of DTCs matching the status mask, plus availability mask and format. */
        fun dtcCount(statusMask: Int = 0xFF) = ReadRequest("Fault count (19 01)", byteArrayOf(0x19, 0x01, statusMask.toByte()))

        /** 19 02 FF: every DTC with its status byte. */
        fun dtcList(statusMask: Int = 0xFF) = ReadRequest("Fault list (19 02)", byteArrayOf(0x19, 0x02, statusMask.toByte()))

        /** 19 03: which DTCs have snapshot (freeze-frame) records, and their record numbers. */
        fun snapshotIdentification() = ReadRequest("Snapshot list (19 03)", byteArrayOf(0x19, 0x03))

        /** 19 04 DTC record: snapshot record(s) for one DTC; record 0xFF requests all records. */
        fun snapshotRecord(dtc: Int, record: Int = 0xFF) =
            ReadRequest("Snapshot for ${Dtc.format(dtc)} (19 04)", byteArrayOf(0x19, 0x04) + Dtc.bytes(dtc) + record.toByte())

        /** 19 06 DTC record: extended data record(s) for one DTC; record 0xFF requests all records. */
        fun extendedData(dtc: Int, record: Int = 0xFF) =
            ReadRequest("Extended data for ${Dtc.format(dtc)} (19 06)", byteArrayOf(0x19, 0x06) + Dtc.bytes(dtc) + record.toByte())

        /** 19 15: DTCs with permanent status, where the ECU supports it. */
        fun permanentDtcs() = ReadRequest("Permanent faults (19 15)", byteArrayOf(0x19, 0x15))
    }
}

internal fun ByteArray.hexString(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
