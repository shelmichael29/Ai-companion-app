package com.aicarcompanion.app.diagnostics.obd

import com.aicarcompanion.app.domain.model.SensorReading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** How the phone talks to an OBD-II adapter (typically an ELM327-compatible dongle). */
enum class ObdTransport(val label: String) {
    BLUETOOTH_CLASSIC("Bluetooth Classic"),
    BLUETOOTH_LE("Bluetooth Low Energy"),
    WIFI("Wi-Fi"),
}

/** Radio capability reported by Android for a paired device. */
enum class AdapterRadio(val label: String) {
    CLASSIC("Bluetooth Classic"),
    DUAL("Classic + Low Energy"),
    LE_ONLY("Low Energy only"),
    UNKNOWN("Unknown type"),
}

/** A paired device the user may choose as their OBD-II adapter. */
data class AdapterDevice(val name: String, val address: String, val radio: AdapterRadio) {
    /** Name-based hint only; any ELM327-compatible Classic adapter can be used. */
    val looksLikeObdAdapter: Boolean
        get() = OBD_NAME_HINTS.any { name.contains(it, ignoreCase = true) }

    private companion object {
        val OBD_NAME_HINTS = listOf("OBD", "ELM", "V-LINK", "VLINK", "VGATE", "PANLONG", "KONNWEI", "VEEPEAK", "BAFX", "CAR")
    }
}

sealed interface ObdConnectionState {
    data object Disconnected : ObdConnectionState
    data class Connecting(val transport: ObdTransport, val adapterName: String? = null) : ObdConnectionState

    /**
     * The adapter link is up. [vehicleProtocol] is the OBD-II protocol the vehicle answered on,
     * or null if the adapter is connected but the vehicle hasn't responded (e.g. ignition off).
     */
    data class Connected(
        val transport: ObdTransport,
        val adapterName: String,
        val vehicleProtocol: String? = null,
        val adapterVersion: String? = null,
    ) : ObdConnectionState

    data class Reconnecting(val adapterName: String, val attempt: Int, val maxAttempts: Int) : ObdConnectionState
    data class Unavailable(val reason: String) : ObdConnectionState
    data class Failed(val message: String) : ObdConnectionState
}

/** Read-only access to a vehicle through an OBD-II adapter. UI and assistant depend on this. */
interface ObdConnection {
    val state: StateFlow<ObdConnectionState>

    /** Current readings, polled only while collected and while the vehicle is responding. */
    val liveReadings: Flow<List<SensorReading>>

    /** PIDs the vehicle reported as supported, empty until it responds. */
    val supportedPids: StateFlow<Set<Int>>

    fun connect(device: AdapterDevice)
    fun disconnect()

    /** Re-checks whether the vehicle responds (e.g. after turning the ignition on). */
    fun retryVehicle()
}
