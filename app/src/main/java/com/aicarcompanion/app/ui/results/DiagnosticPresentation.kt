package com.aicarcompanion.app.ui.results

import com.aicarcompanion.app.diagnostics.dtc.DtcCodeFormat
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DiagnosticTroubleCode
import com.aicarcompanion.app.domain.model.DtcSeverity

enum class UrgencyLevel(val label: String, val shortLabel: String) {
    HIGH("Get it checked as soon as you can", "Important"),
    MEDIUM("Get it checked soon", "Check soon"),
    LOW("Usually minor", "Minor"),
    UNKNOWN("Not sure how serious", "Unknown"),
}

/** Beginner-first view of one diagnostic result. Built only from real (or clearly labeled sample) data. */
data class DiagnosticResultUi(
    val title: String,
    val whatsHappening: String,
    val urgency: UrgencyLevel,
    val urgencyExplanation: String,
    val nextSteps: List<String>,
    val details: List<Pair<String, String>>,
    val isSample: Boolean,
    val sourceLabel: String,
)

object DiagnosticPresentation {

    fun urgencyOf(severity: DtcSeverity) = when (severity) {
        DtcSeverity.SEVERE -> UrgencyLevel.HIGH
        DtcSeverity.MODERATE -> UrgencyLevel.MEDIUM
        DtcSeverity.INFO -> UrgencyLevel.LOW
        DtcSeverity.UNKNOWN -> UrgencyLevel.UNKNOWN
    }

    fun fromCode(dtc: DiagnosticTroubleCode, readLabel: String?): DiagnosticResultUi {
        val urgency = urgencyOf(dtc.severity)
        val known = dtc.explanation != null
        val system = DtcCodeFormat.system(dtc.code)
        val title = when {
            dtc.headline != null -> dtc.headline
            system != null -> "Something in the ${system.plainLabel} was flagged"
            else -> "Your car flagged code ${dtc.code}"
        }
        val steps = buildList {
            when (urgency) {
                UrgencyLevel.HIGH -> {
                    add("Drive gently and avoid hard acceleration.")
                    add("Get it checked by a mechanic as soon as you can.")
                    add("If the check-engine light is flashing or the car runs badly, stop when it's safe and call for help.")
                }
                UrgencyLevel.MEDIUM -> {
                    add("It's usually OK to keep driving for now.")
                    add("Book a check with a mechanic soon.")
                }
                UrgencyLevel.LOW -> add("It's usually fine to keep driving, but it's worth fixing.")
                UrgencyLevel.UNKNOWN -> {
                    add("I don't have details on this code yet.")
                    add("Have a mechanic look, especially if the car drives differently.")
                }
            }
            add("Ask your assistant to explain it in more detail.")
        }
        val details = buildList {
            add("Code" to dtc.code)
            add("Status" to dtc.status.label)
            if (known) add("Definition" to dtc.description)
            dtc.technicalDetail?.let { add("Technical" to it) }
            dtc.diyChecks?.let { add("Things to check" to it) }
            add("Source" to dtc.origin.label + (readLabel?.let { " · $it" } ?: ""))
        }
        return DiagnosticResultUi(
            title = title,
            whatsHappening = dtc.explanation ?: "${dtc.description}. I don't have a plain-language explanation for this code yet.",
            urgency = urgency,
            urgencyExplanation = dtc.severity.advice,
            nextSteps = steps,
            details = details,
            isSample = dtc.origin == DataOrigin.SAMPLE,
            sourceLabel = if (dtc.origin == DataOrigin.SAMPLE) "Sample data, not from your car" else dtc.origin.label,
        )
    }
}
