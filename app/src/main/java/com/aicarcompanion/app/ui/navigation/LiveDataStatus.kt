package com.aicarcompanion.app.ui.navigation

import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.SensorReading
import java.time.Duration
import java.time.Instant

/** What the Live Data screen can honestly say about the current readings. */
sealed interface LiveDataStatus {
    data object NoVehicle : LiveDataStatus
    data object Disconnected : LiveDataStatus
    data object WaitingForData : LiveDataStatus
    /** Built-in demonstration readings, not from the car. */
    data object Sample : LiveDataStatus
    data object Live : LiveDataStatus
    data class Stale(val lastUpdate: Instant) : LiveDataStatus

    /** True when values may be shown to the user. */
    val showsValues: Boolean get() = this is Sample || this is Live || this is Stale

    companion object {
        val STALE_AFTER: Duration = Duration.ofSeconds(10)

        fun of(hasVehicle: Boolean, readings: List<SensorReading>, obdState: ObdConnectionState, now: Instant): LiveDataStatus {
            if (!hasVehicle) return NoVehicle
            if (readings.isNotEmpty() && readings.all { it.origin == DataOrigin.SAMPLE }) return Sample
            val connected = obdState is ObdConnectionState.Connected
            if (!connected) return Disconnected
            val newest = readings.maxOfOrNull { it.timestamp } ?: return WaitingForData
            return if (Duration.between(newest, now) > STALE_AFTER) Stale(newest) else Live
        }

        /** "just now", "5 s ago", "2 min ago". */
        fun ageLabel(timestamp: Instant, now: Instant): String {
            val seconds = Duration.between(timestamp, now).seconds.coerceAtLeast(0)
            return when {
                seconds < 2 -> "just now"
                seconds < 60 -> "$seconds s ago"
                seconds < 3600 -> "${seconds / 60} min ago"
                else -> "over an hour ago"
            }
        }
    }
}
