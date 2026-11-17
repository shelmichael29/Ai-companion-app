package com.aicarcompanion.app.diagnostics.obd

import androidx.core.content.edit
import android.content.SharedPreferences
import com.aicarcompanion.app.domain.model.Vehicle

/** Steps while connecting, so the UI never calls a Bluetooth link a vehicle connection. */
enum class ConnectPhase {
    /** Opening the Bluetooth socket. */
    OPENING_LINK,
    /** Socket open; the adapter hasn't been initialized yet. */
    LINK_OPEN,
    /** Sending the ELM327 setup commands (ATZ, ATE0, …). */
    INITIALIZING_ADAPTER,
    /** Adapter ready; asking the vehicle for its supported data (0100), possibly trying protocols. */
    WAITING_FOR_VEHICLE,
    /** A vehicle ECU answered with valid data; finishing setup. */
    ECU_RESPONDING,
}

/** Health of an established vehicle connection. */
enum class LinkHealth {
    OK,
    /** Several requests in a row got no valid answer from the vehicle. */
    DEGRADED,
    /** Automatically re-checking the vehicle or re-initializing the adapter. */
    RECOVERING,
}

/**
 * One user-facing stage for the whole vehicle connection. "Vehicle connected" requires a valid
 * ECU answer: a Bluetooth connection alone is never reported as a vehicle connection.
 */
enum class VehicleLinkStage(val label: String) {
    ADAPTER_NOT_FOUND("Adapter not found"),
    ADAPTER_DISCOVERED("Adapter found"),
    PAIRING_REQUIRED("Pairing required"),
    ADAPTER_CONNECTED("Adapter connected"),
    ADAPTER_INITIALIZING("Adapter initializing"),
    VEHICLE_PENDING("Waiting for the vehicle"),
    ECU_RESPONDING("Vehicle responding"),
    VEHICLE_CONNECTED("Vehicle connected"),
    DEGRADED("Connection unstable"),
    RECOVERING("Recovering connection"),
    FAILED("Connection failed"),
    ;

    /** True only when real vehicle data can be read right now. */
    val vehicleReady: Boolean get() = this == VEHICLE_CONNECTED

    /** Plain words for Everyday Driver users (no protocols or codes). */
    val everydayMessage: String
        get() = when (this) {
            ADAPTER_NOT_FOUND -> "I can't find your scanner. Make sure it's plugged in and Bluetooth is on."
            ADAPTER_DISCOVERED -> "Your scanner is ready to connect."
            PAIRING_REQUIRED -> "Pair your OBD-II scanner in your phone's Bluetooth settings first."
            ADAPTER_CONNECTED, ADAPTER_INITIALIZING -> "Connecting to your scanner…"
            VEHICLE_PENDING -> "Your scanner is connected, but your vehicle hasn't responded. I'm checking the connection."
            ECU_RESPONDING -> "Your car is answering. Almost ready…"
            VEHICLE_CONNECTED -> "Connected to your car."
            DEGRADED, RECOVERING -> "Your car stopped answering for a moment. I'm reconnecting."
            FAILED -> "I couldn't connect to your car. Check the scanner and try again."
        }

    companion object {
        /**
         * @param pairedAdapterAvailable whether a paired device that looks like an OBD-II adapter
         *   exists (null if unknown, e.g. Bluetooth permission not granted yet).
         */
        fun of(state: ObdConnectionState, pairedAdapterAvailable: Boolean? = null): VehicleLinkStage = when (state) {
            ObdConnectionState.Disconnected -> when (pairedAdapterAvailable) {
                true -> ADAPTER_DISCOVERED
                false -> PAIRING_REQUIRED
                null -> ADAPTER_NOT_FOUND
            }
            is ObdConnectionState.Connecting -> when (state.phase) {
                ConnectPhase.OPENING_LINK -> ADAPTER_DISCOVERED
                ConnectPhase.LINK_OPEN -> ADAPTER_CONNECTED
                ConnectPhase.INITIALIZING_ADAPTER -> ADAPTER_INITIALIZING
                ConnectPhase.WAITING_FOR_VEHICLE -> VEHICLE_PENDING
                ConnectPhase.ECU_RESPONDING -> ECU_RESPONDING
            }
            is ObdConnectionState.Connected -> when {
                state.vehicleProtocol == null -> VEHICLE_PENDING
                state.health == LinkHealth.DEGRADED -> DEGRADED
                state.health == LinkHealth.RECOVERING -> RECOVERING
                else -> VEHICLE_CONNECTED
            }
            is ObdConnectionState.Reconnecting -> RECOVERING
            is ObdConnectionState.Unavailable, is ObdConnectionState.Failed -> FAILED
        }
    }
}

/**
 * Remembers which OBD-II protocol each vehicle answered on, so the next connection tries it
 * first. Keyed by Penelope's vehicle ID (never the VIN).
 */
interface ProtocolMemory {
    fun lastWorking(vehicleId: String): ObdProtocol?
    fun remember(vehicleId: String, protocol: ObdProtocol)
}

class InMemoryProtocolMemory : ProtocolMemory {
    private val map = mutableMapOf<String, ObdProtocol>()
    override fun lastWorking(vehicleId: String) = map[vehicleId]
    override fun remember(vehicleId: String, protocol: ObdProtocol) { map[vehicleId] = protocol }
}

class SharedPreferencesProtocolMemory(private val prefs: SharedPreferences) : ProtocolMemory {
    override fun lastWorking(vehicleId: String): ObdProtocol? =
        prefs.getString(key(vehicleId), null)?.let { code -> ObdProtocol.entries.firstOrNull { it.name == code } }
    override fun remember(vehicleId: String, protocol: ObdProtocol) { prefs.edit { putString(key(vehicleId), protocol.name) } }
    private fun key(vehicleId: String) = "protocol_for_$vehicleId"
}

/**
 * Which explicit protocols to try (in order) when the adapter's automatic search finds nothing.
 * Generic OBD-II rules only. A future vehicle database can provide make- or model-specific
 * profiles behind this interface. Manufacturer diagnostics (e.g. BMW ENET) are a separate
 * transport and are never handled here.
 */
interface VehicleProtocolProfile {
    fun fallbackOrder(vehicle: Vehicle?): List<ObdProtocol>
}

object GenericProtocolProfile : VehicleProtocolProfile {
    /**
     * CAN (ISO 15765-4) is required for OBD-II on US cars from model year 2008, and 11-bit 500 kbaud
     * is by far the most common. Older or unknown-year cars may also use K-line protocols.
     */
    override fun fallbackOrder(vehicle: Vehicle?): List<ObdProtocol> {
        val year = vehicle?.year
        return if (year != null && year >= 2008) {
            listOf(ObdProtocol.CAN_11_500, ObdProtocol.CAN_29_500)
        } else {
            listOf(ObdProtocol.CAN_11_500, ObdProtocol.CAN_29_500, ObdProtocol.ISO_9141_2, ObdProtocol.KWP_FAST)
        }
    }
}

/** Limits for automatic recovery, so it can never loop forever or drain the battery. */
object RecoveryPolicy {
    /** Consecutive vehicle requests without a valid answer before the link counts as degraded. */
    const val DEGRADE_AFTER = 3
    /** Waits before each re-check of the vehicle (bounded, increasing). */
    val RECHECK_DELAYS_MILLIS = listOf(1_000L, 3_000L, 8_000L)
}
