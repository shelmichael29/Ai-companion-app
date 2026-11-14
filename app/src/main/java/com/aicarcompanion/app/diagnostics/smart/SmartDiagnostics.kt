package com.aicarcompanion.app.diagnostics.smart

import com.aicarcompanion.app.diagnostics.obd.ObdPid
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.Vehicle
import java.time.Instant

/*
 * Penelope Smart Diagnostics — FOUNDATION ONLY.
 *
 * These types define how a future diagnostic engine will combine a symptom description, the
 * vehicle profile, history and live OBD-II evidence into an assessment. No reasoning engine
 * ships yet. Invariants enforced here:
 *  - every piece of evidence carries its origin and time;
 *  - a cause can only be CONFIRMED with a recorded confirmation test result;
 *  - missing data is explicit (not every vehicle/adapter supports every reading).
 */

/** A user's description of a problem, plus answers to follow-up questions. */
data class SymptomReport(
    val vehicleId: String,
    val description: String,
    val category: SymptomCategory?,
    val answers: List<Pair<String, String>> = emptyList(),
    val reportedAt: Instant,
)

sealed interface DiagnosticEvidence {
    val origin: DataOrigin
    val observedAt: Instant

    data class TroubleCode(val code: String, val status: String, override val origin: DataOrigin, override val observedAt: Instant) : DiagnosticEvidence
    data class SensorValue(val pid: ObdPid, val value: Double, override val origin: DataOrigin, override val observedAt: Instant) : DiagnosticEvidence
    data class UserAnswer(val question: String, val answer: String, override val observedAt: Instant) : DiagnosticEvidence {
        override val origin: DataOrigin get() = DataOrigin.USER_ENTERED
    }
    data class HistoryItem(val summary: String, override val origin: DataOrigin, override val observedAt: Instant) : DiagnosticEvidence
}

enum class CauseConfidence(val plainLabel: String) {
    /** Plausible from general knowledge; nothing specific supports it yet. */
    POSSIBLE("Possible"),
    /** Specific evidence (codes, readings, answers) points toward it. Still unconfirmed. */
    SUPPORTED_BY_EVIDENCE("Likely, based on the evidence so far"),
    /** Verified by a test or inspection. */
    CONFIRMED("Confirmed by testing"),
}

data class ConfirmationTest(val description: String, val result: String?, val performedAt: Instant?)

data class PossibleCause(
    val title: String,
    val plainExplanation: String,
    val confidence: CauseConfidence,
    val supportingEvidence: List<DiagnosticEvidence> = emptyList(),
    val contradictingEvidence: List<DiagnosticEvidence> = emptyList(),
    /** Tests to run before replacing parts. */
    val confirmationTests: List<ConfirmationTest> = emptyList(),
) {
    init {
        require(confidence != CauseConfidence.CONFIRMED || confirmationTests.any { it.result != null }) {
            "A cause can only be marked CONFIRMED with a recorded confirmation test result."
        }
        require(confidence != CauseConfidence.SUPPORTED_BY_EVIDENCE || supportingEvidence.isNotEmpty()) {
            "SUPPORTED_BY_EVIDENCE requires at least one piece of supporting evidence."
        }
    }
}

enum class Urgency(val plainLabel: String) {
    STOP_SAFELY("Pull over safely and stop driving"),
    SOON("Get it checked soon"),
    WHEN_CONVENIENT("Get it checked when convenient"),
    MONITOR("Keep an eye on it"),
}

/** A live reading or service the engine would like to have. */
data class DataRequirement(val pid: ObdPid?, val description: String, val requiresManufacturerSupport: Boolean = false)

data class DiagnosticAssessment(
    val report: SymptomReport,
    val urgency: Urgency,
    val causes: List<PossibleCause>,
    val nextSteps: List<String>,
    /** Data that would help but wasn't available (unsupported PID, no adapter, etc.). */
    val missingData: List<DataRequirement>,
    val recommendMechanic: Boolean,
)

/** Future engine. Implementations must never fabricate readings or present a POSSIBLE cause as confirmed. */
interface SmartDiagnosticsEngine {
    suspend fun assess(report: SymptomReport, vehicle: Vehicle?, evidence: List<DiagnosticEvidence>): DiagnosticAssessment
}

/** Which standard readings help investigate each symptom. Used to plan what to read when an adapter is connected. */
object DiagnosticDataPlan {
    fun forCategory(category: SymptomCategory): List<DataRequirement> = when (category) {
        SymptomCategory.SHAKING, SymptomCategory.FLASHING_CHECK_ENGINE -> listOf(
            DataRequirement(ObdPid.ENGINE_RPM, "Idle stability"),
            DataRequirement(null, "Stored, pending and permanent trouble codes"),
            DataRequirement(null, "Per-cylinder misfire counters (mode 06), if the vehicle reports them"),
            DataRequirement(ObdPid.SHORT_TERM_FUEL_TRIM_B1, "Short-term fuel trim"),
            DataRequirement(ObdPid.LONG_TERM_FUEL_TRIM_B1, "Long-term fuel trim"),
        )
        SymptomCategory.POOR_FUEL_ECONOMY, SymptomCategory.LOSS_OF_POWER -> listOf(
            DataRequirement(ObdPid.SHORT_TERM_FUEL_TRIM_B1, "Short-term fuel trim"),
            DataRequirement(ObdPid.LONG_TERM_FUEL_TRIM_B1, "Long-term fuel trim"),
            DataRequirement(ObdPid.MAF_RATE, "Mass air flow"),
            DataRequirement(ObdPid.INTAKE_MAP, "Intake manifold pressure"),
            DataRequirement(ObdPid.COOLANT_TEMP, "Engine temperature (a cold-reading sensor wastes fuel)"),
            DataRequirement(null, "Oxygen / air-fuel sensor readings"),
            DataRequirement(null, "Freeze-frame data for any stored code"),
        )
        SymptomCategory.OVERHEATING -> listOf(
            DataRequirement(ObdPid.COOLANT_TEMP, "Engine temperature"),
            DataRequirement(null, "Cooling fan and pump status", requiresManufacturerSupport = true),
        )
        SymptomCategory.HARD_START -> listOf(
            DataRequirement(ObdPid.CONTROL_MODULE_VOLTAGE, "Battery voltage"),
            DataRequirement(null, "Stored trouble codes"),
        )
        SymptomCategory.BRAKES, SymptomCategory.NOISE -> listOf(
            DataRequirement(null, "ABS and chassis module data", requiresManufacturerSupport = true),
        )
        else -> listOf(DataRequirement(null, "Stored and pending trouble codes"))
    }
}
