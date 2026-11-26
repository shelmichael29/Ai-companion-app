package com.aicarcompanion.app.dashboard

import com.aicarcompanion.app.diagnostics.obd.ConnectPhase
import com.aicarcompanion.app.diagnostics.obd.LinkHealth
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.domain.livedata.SensorValueState
import java.time.Instant

/**
 * How far the DIY Overview can honestly say the connection has got. A Bluetooth link alone is never
 * "the vehicle is responding": [ECU_RESPONDING] needs a vehicle protocol (or the connect phase that
 * means an ECU answered). Sample readings never raise the stage.
 */
enum class DiyConnectionStage {
    /** No adapter link (or a connection attempt that has not opened the link yet). */
    DISCONNECTED,

    /** The Bluetooth link is open; the adapter has not been set up yet. Nothing is known about the vehicle. */
    BLUETOOTH_CONNECTED,

    /** The adapter is set up, but the vehicle has not answered (for example, ignition off). */
    ADAPTER_READY,

    /** A vehicle ECU answered with valid data. */
    ECU_RESPONDING,

    /** The vehicle answered earlier, but data is old or the link is unstable. */
    STALE,

    /** The connection failed or is not available. */
    ERROR,
}

/** One step of the Bluetooth / Adapter / Vehicle ECU progression. */
enum class StepState { DONE, ACTIVE, PENDING, FAILED }

/** What the Overview shows about the connection. Never contains a device address. */
data class DiyConnectionStatus(
    val stage: DiyConnectionStage,
    /** Something is being attempted right now (connecting, initializing, recovering). */
    val inProgress: Boolean,
    /** One plain sentence for the stage. */
    val linkLabel: String,
    /** OBD-II protocol the vehicle answered on; null until it has answered. */
    val protocol: String? = null,
    /** Adapter version or name; never an address. */
    val adapter: String? = null,
    /** When the newest real reading arrived; set only for [DiyConnectionStage.STALE]. */
    val lastDataAt: Instant? = null,
) {
    /** The Bluetooth link. */
    val link: StepState
        get() = when (stage) {
            DiyConnectionStage.DISCONNECTED -> if (inProgress) StepState.ACTIVE else StepState.PENDING
            DiyConnectionStage.ERROR -> StepState.FAILED
            else -> StepState.DONE
        }

    /** The adapter set up (ELM327 initialization). */
    val adapterStep: StepState
        get() = when (stage) {
            DiyConnectionStage.DISCONNECTED, DiyConnectionStage.ERROR -> StepState.PENDING
            DiyConnectionStage.BLUETOOTH_CONNECTED -> if (inProgress) StepState.ACTIVE else StepState.PENDING
            else -> StepState.DONE
        }

    /** The vehicle's ECU answering. Only [DiyConnectionStage.ECU_RESPONDING] is DONE. */
    val ecuStep: StepState
        get() = when (stage) {
            DiyConnectionStage.ECU_RESPONDING -> StepState.DONE
            DiyConnectionStage.ADAPTER_READY -> if (inProgress) StepState.ACTIVE else StepState.PENDING
            DiyConnectionStage.STALE -> if (inProgress) StepState.ACTIVE else StepState.FAILED
            else -> StepState.PENDING
        }
}

/** Pure mapping from the real connection state (and what the sensors show) to a [DiyConnectionStatus]. */
object DiyConnectionMapper {
    const val IGNITION_HINT = "Adapter is ready, but the vehicle is not responding. Is the ignition on?"

    /**
     * @param sensors the current Live Data states; only Live/Stale ones matter, [SensorValueState.Sample]
     *   is ignored on purpose so demonstration values can never make the connection look better.
     */
    fun map(state: ObdConnectionState, sensors: Collection<SensorValueState>, now: Instant): DiyConnectionStatus {
        return when (state) {
            ObdConnectionState.Disconnected ->
                DiyConnectionStatus(DiyConnectionStage.DISCONNECTED, false, "No adapter connected")
            is ObdConnectionState.Connecting -> connecting(state)
            is ObdConnectionState.Connected -> connected(state, sensors, now)
            is ObdConnectionState.Reconnecting ->
                DiyConnectionStatus(DiyConnectionStage.DISCONNECTED, true, "Reconnecting to the adapter (attempt ${state.attempt} of ${state.maxAttempts})")
            is ObdConnectionState.Failed -> DiyConnectionStatus(DiyConnectionStage.ERROR, false, state.message)
            is ObdConnectionState.Unavailable -> DiyConnectionStatus(DiyConnectionStage.ERROR, false, "Adapter unavailable")
        }
    }

    private fun connecting(s: ObdConnectionState.Connecting): DiyConnectionStatus = when (s.phase) {
        ConnectPhase.OPENING_LINK -> DiyConnectionStatus(DiyConnectionStage.DISCONNECTED, true, "Opening the Bluetooth link")
        ConnectPhase.LINK_OPEN ->
            DiyConnectionStatus(DiyConnectionStage.BLUETOOTH_CONNECTED, true, "Bluetooth connected. Setting up the adapter")
        ConnectPhase.INITIALIZING_ADAPTER ->
            DiyConnectionStatus(DiyConnectionStage.BLUETOOTH_CONNECTED, true, "Bluetooth connected. Initializing the adapter")
        ConnectPhase.WAITING_FOR_VEHICLE ->
            DiyConnectionStatus(DiyConnectionStage.ADAPTER_READY, true, "Adapter ready. Waiting for the vehicle")
        ConnectPhase.ECU_RESPONDING ->
            DiyConnectionStatus(DiyConnectionStage.ECU_RESPONDING, true, "Vehicle answered. Finishing setup")
    }

    private fun connected(s: ObdConnectionState.Connected, sensors: Collection<SensorValueState>, now: Instant): DiyConnectionStatus {
        val adapter = (s.adapterVersion ?: s.adapterName).takeIf { it.isNotBlank() && !MAC.containsMatchIn(it) }
        if (s.vehicleProtocol == null) {
            return DiyConnectionStatus(DiyConnectionStage.ADAPTER_READY, false, IGNITION_HINT, adapter = adapter)
        }
        // Staleness itself is decided by the sensor mapper; `now` only keeps a reading's time from being in the future.
        val newestStale = sensors.filterIsInstance<SensorValueState.Stale>().maxOfOrNull { it.lastAt }?.let { minOf(it, now) }
        val anyLive = sensors.any { it is SensorValueState.Live }
        return when {
            s.health == LinkHealth.DEGRADED ->
                DiyConnectionStatus(DiyConnectionStage.STALE, false, "The vehicle stopped answering reliably", s.vehicleProtocol, adapter, newestStale)
            s.health == LinkHealth.RECOVERING ->
                DiyConnectionStatus(DiyConnectionStage.STALE, true, "Reconnecting to the vehicle", s.vehicleProtocol, adapter, newestStale)
            newestStale != null && !anyLive ->
                DiyConnectionStatus(DiyConnectionStage.STALE, false, "The vehicle stopped sending new readings", s.vehicleProtocol, adapter, newestStale)
            else -> DiyConnectionStatus(DiyConnectionStage.ECU_RESPONDING, false, "The vehicle is answering requests", s.vehicleProtocol, adapter)
        }
    }

    private val MAC = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
}
