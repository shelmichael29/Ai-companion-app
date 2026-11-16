package com.aicarcompanion.app.ui.home

import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DiagnosticTroubleCode
import com.aicarcompanion.app.domain.model.DtcSeverity
import com.aicarcompanion.app.domain.model.TroubleCodeRead
import com.aicarcompanion.app.domain.model.Vehicle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Tone of the health summary. Each tone also has its own icon and words, never color alone. */
enum class HealthTone { GOOD, ATTENTION, URGENT, UNKNOWN }

/** What the primary button under the health summary does. */
enum class HealthAction { SEE_DETAILS, CHECK_CODES, CONNECT_ADAPTER, ADD_VEHICLE }

/**
 * The Everyday Driver health summary, in plain words.
 *
 * Rules: "All clear" is only shown after a real code read from the car; sample data is always
 * labeled; missing information is explained instead of guessed. No scores or readings are invented.
 */
data class EverydayHealth(
    /** Short, bold status, e.g. "All clear". */
    val headline: String,
    /** One or two plain sentences under the headline. */
    val detail: String,
    val tone: HealthTone,
    /** Up to two codes to show, with a plain-language meaning. */
    val codes: List<Pair<String, String>>,
    /** e.g. "Checked Nov 16, 2:34 PM", or null if never checked. */
    val updated: String?,
    val actionLabel: String,
    val action: HealthAction,
    /** True when what's shown is sample data, not the user's car. */
    val sample: Boolean,
)

private val CHECKED_FORMAT = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

fun everydayHealth(
    vehicle: Vehicle?,
    codes: List<DiagnosticTroubleCode>,
    lastRead: TroubleCodeRead?,
    obd: ObdConnectionState,
    zone: ZoneId = ZoneId.systemDefault(),
): EverydayHealth {
    val connected = obd is ObdConnectionState.Connected && obd.vehicleProtocol != null
    val sample = codes.any { it.origin == DataOrigin.SAMPLE } || lastRead?.origin == DataOrigin.SAMPLE
    val updated = lastRead?.readAt?.let { "Checked ${checked(it, zone)}" }
    val checkAction = if (connected) HealthAction.CHECK_CODES else HealthAction.CONNECT_ADAPTER
    val checkLabel = if (connected) "Check my car now" else "Connect my adapter"

    return when {
        vehicle == null -> EverydayHealth(
            "Add your car", "Tell me which car you drive and I can explain warnings and maintenance for it.",
            HealthTone.UNKNOWN, emptyList(), null, "Add my car", HealthAction.ADD_VEHICLE, sample = false,
        )
        codes.isNotEmpty() -> {
            val urgent = codes.any { it.severity == DtcSeverity.SEVERE }
            val count = if (codes.size == 1) "1 warning code" else "${codes.size} warning codes"
            EverydayHealth(
                headline = if (urgent) "Needs attention soon" else "Needs attention",
                detail = (if (sample) "Sample data, not from your car. " else "") +
                    "I found $count. Tap below and I'll explain what it means and what to do.",
                tone = if (urgent) HealthTone.URGENT else HealthTone.ATTENTION,
                codes = codes.take(2).map { it.code to (it.headline ?: it.description) },
                updated = updated,
                actionLabel = "See what it means",
                action = HealthAction.SEE_DETAILS,
                sample = sample,
            )
        }
        lastRead != null -> EverydayHealth(
            headline = "All clear",
            detail = (if (sample) "Sample data, not from your car. " else "") +
                "No warning codes the last time I checked." + if (connected) "" else " Connect your adapter for a fresh check.",
            tone = HealthTone.GOOD, codes = emptyList(), updated = updated,
            actionLabel = checkLabel, action = checkAction, sample = sample,
        )
        connected -> EverydayHealth(
            "Ready to check", "Your adapter is connected. I haven't checked for warning codes yet.",
            HealthTone.UNKNOWN, emptyList(), null, checkLabel, checkAction, sample = false,
        )
        else -> EverydayHealth(
            "Not checked yet",
            "I can't see your car's health without an OBD-II adapter. Plug one in under the dashboard and I'll check for warning codes.",
            HealthTone.UNKNOWN, emptyList(), null, checkLabel, checkAction, sample = false,
        )
    }
}

private fun checked(at: Instant, zone: ZoneId): String = CHECKED_FORMAT.format(at.atZone(zone))

/** Short connection words for the header, plus whether the link is up. */
fun everydayConnection(obd: ObdConnectionState): Pair<String, Boolean> = when (obd) {
    is ObdConnectionState.Connected -> (if (obd.vehicleProtocol != null) "Connected" else "Adapter on · car not responding") to (obd.vehicleProtocol != null)
    is ObdConnectionState.Connecting -> "Connecting…" to false
    is ObdConnectionState.Reconnecting -> "Reconnecting…" to false
    else -> "Not connected" to false
}
