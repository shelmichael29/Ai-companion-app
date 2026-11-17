package com.aicarcompanion.app.connectivity

import com.aicarcompanion.app.diagnostics.obd.LinkHealth
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * Turns vehicle connection changes into short things Penelope can say. Only meaningful, settled
 * changes are announced (connected, car not answering, lost, restored, failed), never every
 * intermediate step, so voice conversations aren't cluttered. Delivery at a safe moment between
 * turns is the voice session's job (VoiceSession.queueStatusNotice).
 */
object ConnectionAnnouncements {
    const val CONNECTED = "You're connected to your car."
    const val CAR_SILENT = "Your scanner is connected, but your car hasn't responded yet. Is the ignition on?"
    const val CAR_STOPPED = "Your car stopped answering for a moment. I'm reconnecting."
    const val ADAPTER_LOST = "Your adapter disconnected. I'm reconnecting."
    const val RESTORED = "Connection restored. We can continue."
    const val FAILED = "I couldn't connect to your adapter. Let's try again."

    private fun ready(s: ObdConnectionState?) = s is ObdConnectionState.Connected && s.vehicleProtocol != null && s.health == LinkHealth.OK
    private fun unhealthy(s: ObdConnectionState?) = s is ObdConnectionState.Connected && s.vehicleProtocol != null && s.health != LinkHealth.OK
    private fun silent(s: ObdConnectionState?) = s is ObdConnectionState.Connected && s.vehicleProtocol == null

    fun between(previous: ObdConnectionState?, current: ObdConnectionState): String? = when {
        ready(current) && (unhealthy(previous) || previous is ObdConnectionState.Reconnecting) -> RESTORED
        ready(current) && !ready(previous) -> CONNECTED
        silent(current) && !silent(previous) -> CAR_SILENT
        unhealthy(current) && ready(previous) -> CAR_STOPPED
        current is ObdConnectionState.Reconnecting && previous is ObdConnectionState.Connected -> ADAPTER_LOST
        current is ObdConnectionState.Failed && previous != null && previous !is ObdConnectionState.Failed &&
            previous != ObdConnectionState.Disconnected -> FAILED
        else -> null
    }
}

/** Watches the vehicle connection and hands meaningful changes to [announce]. */
class ConnectionAnnouncer(private val scope: CoroutineScope, private val announce: (String) -> Unit) {
    private var job: Job? = null

    fun watch(states: Flow<ObdConnectionState>) {
        job?.cancel()
        job = scope.launch {
            var previous: ObdConnectionState? = null
            var lastSaid: String? = null
            // Only the parts that matter for announcements; e.g. reconnect attempt numbers are ignored.
            states.distinctUntilChangedBy { key(it) }.collect { state ->
                ConnectionAnnouncements.between(previous, state)?.let { text ->
                    // Never the same message twice in a row (e.g. once per retry attempt).
                    if (text != lastSaid) { announce(text); lastSaid = text }
                }
                previous = state
            }
        }
    }

    private fun key(s: ObdConnectionState): Any = when (s) {
        is ObdConnectionState.Connected -> Triple("connected", s.vehicleProtocol != null, s.health)
        is ObdConnectionState.Reconnecting -> "reconnecting"
        is ObdConnectionState.Connecting -> "connecting"
        is ObdConnectionState.Failed -> "failed"
        is ObdConnectionState.Unavailable -> "unavailable"
        ObdConnectionState.Disconnected -> "disconnected"
    }
}
