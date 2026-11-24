package com.aicarcompanion.enet.dtc

import com.aicarcompanion.enet.F10Module

/**
 * Everything the user must explicitly confirm before fault memory is cleared. Each flag maps
 * to one warning shown in the tester; none has a default of true.
 */
data class ClearConfirmation(
    /** "Freeze-frame (snapshot) and extended fault data may be deleted." */
    val freezeFrameLossAccepted: Boolean,
    /** "Clearing the engine computer may reset emissions readiness monitors." Required when DME is included. */
    val emissionsReadinessAccepted: Boolean,
    /** "Permanent or currently active faults may remain or return immediately." */
    val faultsMayRemainAccepted: Boolean,
    /** Initial live testing: vehicle stationary, ignition on, engine off. */
    val stationaryIgnitionOnEngineOff: Boolean,
)

/** Proof that the gate approved one clear of specific modules. Single use, time limited. */
class ClearAuthorization internal constructor(
    val reportId: String,
    val modules: List<F10Module>,
    /** Fault records read before clearing, for the before/after comparison. */
    val before: Map<F10Module, List<DtcRecord>>,
    val expiresAtMillis: Long,
) {
    internal var used = false
        private set

    internal fun consume(now: Long): ClearCommand? {
        if (used || now > expiresAtMillis) return null
        used = true
        return ClearCommand.ALL_GROUPS
    }
}

/** The single clear request this codebase can build: 14 FF FF FF. No single-DTC clearing. */
internal class ClearCommand private constructor(val uds: ByteArray) {
    companion object {
        val ALL_GROUPS = ClearCommand(byteArrayOf(Uds.SID_CLEAR_DTC.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
    }
}

sealed interface ClearDecision {
    data class Authorized(val authorization: ClearAuthorization) : ClearDecision
    data class Refused(val reasons: List<String>) : ClearDecision
}

/**
 * The only way to obtain a [ClearAuthorization]. It requires a fresh fault read that has been
 * saved locally, every warning confirmed, and only modules whose fault list was actually read.
 */
class ClearGate(private val clock: () -> Long, private val validityMillis: Long = DEFAULT_VALIDITY_MILLIS) {

    fun authorize(
        report: DiagnosticReport,
        savedReportId: String?,
        modules: List<F10Module>,
        confirmation: ClearConfirmation,
    ): ClearDecision {
        val selected = modules.distinct()
        val reasons = buildList {
            if (savedReportId != report.id) add("Save the current diagnostic report before clearing.")
            if (report.cancelled) add("The last scan was cancelled; run a complete read of the selected modules first.")
            if (selected.isEmpty()) add("Select at least one module to clear.")
            val eligible = report.clearEligible.toSet()
            selected.filterNot { it in eligible }.forEach {
                add("${it.title} (${it.name}) isn't eligible: its fault list wasn't read or holds no faults.")
            }
            if (!confirmation.freezeFrameLossAccepted) add("Confirm that freeze-frame and extended fault data may be deleted.")
            if (F10Module.DME in selected && !confirmation.emissionsReadinessAccepted) {
                add("Confirm that clearing the engine computer may reset emissions readiness.")
            }
            if (!confirmation.faultsMayRemainAccepted) add("Confirm that permanent or active faults may remain.")
            if (!confirmation.stationaryIgnitionOnEngineOff) add("Confirm the vehicle is stationary with ignition on and engine off.")
        }
        if (reasons.isNotEmpty()) return ClearDecision.Refused(reasons)
        val before = report.modules.filter { it.module in selected }.associate { m -> m.module to m.faults.orEmpty().map { it.record } }
        return ClearDecision.Authorized(ClearAuthorization(report.id, selected, before, clock() + validityMillis))
    }

    companion object {
        const val DEFAULT_VALIDITY_MILLIS = 120_000L
    }
}
