package com.aicarcompanion.app.diagnostics.obd

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the phone talks to an OBD-II adapter (typically an ELM327-compatible dongle). */
enum class ObdTransport(val label: String) {
    BLUETOOTH_CLASSIC("Bluetooth (classic SPP)"),
    BLUETOOTH_LE("Bluetooth Low Energy"),
    WIFI("Wi-Fi"),
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

/**
 * A byte/text pipe to an OBD-II adapter. Each transport (Bluetooth classic, BLE, Wi-Fi TCP)
 * will provide one implementation; everything above this interface (command sequencing,
 * parsing with [Elm327ResponseParser], polling) is transport-independent.
 */
interface ObdConnection {
    val state: StateFlow<ObdConnectionState>

    suspend fun connect()
    suspend fun disconnect()

    /**
     * Sends one ELM327 command (e.g. "010C") and returns the raw text response.
     * Implementations must time out rather than block forever.
     */
    suspend fun send(command: String): String
}

/**
 * Placeholder used until a real transport exists. It never fabricates vehicle data: it
 * simply reports that no adapter support is available.
 */
class UnavailableObdConnection : ObdConnection {
    private val _state = MutableStateFlow<ObdConnectionState>(
        ObdConnectionState.Unavailable("OBD-II adapter support is not built yet"),
    )
    override val state: StateFlow<ObdConnectionState> = _state.asStateFlow()

    override suspend fun connect() = Unit
    override suspend fun disconnect() = Unit

    override suspend fun send(command: String): String =
        throw UnsupportedOperationException("No OBD-II adapter connection is available")
}
