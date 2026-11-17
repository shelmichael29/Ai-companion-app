package com.aicarcompanion.app.diagnostics.bluetooth

import com.aicarcompanion.app.diagnostics.obd.AdapterDevice
import com.aicarcompanion.app.diagnostics.obd.ElmLinkFactory
import kotlinx.coroutines.flow.Flow
import java.io.IOException

/**
 * Why a connection to an adapter didn't work. Each reason has plain words for Everyday Driver
 * users and says whether trying again could help. Technical details stay in
 * [AdapterConnectException.detail] and are only shown to DIY/Advanced users.
 */
enum class ConnectFailure(val everydayMessage: String, val retryable: Boolean) {
    BLUETOOTH_OFF("Bluetooth is off. Turn it on and I'll try again.", retryable = false),
    PERMISSION_DENIED("I need permission to use Bluetooth to find your adapter.", retryable = false),
    NOT_SUPPORTED("This phone doesn't have Bluetooth.", retryable = false),
    NOT_FOUND("I couldn't find your car adapter. Make sure it's plugged in and the ignition is on.", retryable = false),
    UNSUPPORTED_ADAPTER("That adapter uses Bluetooth Low Energy, which I can't use yet. A Bluetooth Classic adapter will work.", retryable = false),
    TIMEOUT("Your adapter didn't answer in time.", retryable = true),
    ADAPTER_UNAVAILABLE("I couldn't reach your adapter. It may be unplugged, out of range, or connected to another app.", retryable = true),
    UNKNOWN("Something went wrong while connecting.", retryable = true),
}

/** A connection failure with a reason. [detail] is technical and never shown in Easy Mode. */
class AdapterConnectException(val reason: ConnectFailure, val detail: String? = null) :
    IOException("${reason.name}${detail?.let { ": $it" } ?: ""}")

/**
 * A way to reach OBD-II adapters. The Android Bluetooth Classic implementation is
 * [BluetoothGateway]; tests use fakes. Future transports (BLE, Wi-Fi, USB) implement the same
 * interface. BMW ENET is a different protocol and is not an [AdapterTransport].
 */
interface AdapterTransport : ElmLinkFactory {
    fun availability(): BluetoothAvailability

    /** Runtime permissions this Android version needs for discovery and connection. */
    val requiredPermissions: List<String>

    /** Whether searching for nearby devices is allowed (scan permission, or location before Android 12). */
    fun canDiscover(): Boolean

    /** Adapters the phone is already paired with, likely OBD-II adapters first. */
    fun pairedDevices(): List<AdapterDevice>

    /**
     * Searches for nearby Bluetooth Classic devices for at most [timeoutMillis], emitting each one
     * found. The search stops when collection stops or the time is up, so it can't drain the battery.
     */
    fun discover(timeoutMillis: Long): Flow<AdapterDevice>
}

/** Maps an Android Bluetooth exception to a plain reason. Messages vary by phone, so this is lenient. */
object ConnectFailureClassifier {
    fun classify(e: Throwable): ConnectFailure {
        if (e is AdapterConnectException) return e.reason
        val m = e.message.orEmpty().lowercase()
        return when {
            "permission" in m || e is SecurityException -> ConnectFailure.PERMISSION_DENIED
            "timed out" in m || "timeout" in m -> ConnectFailure.TIMEOUT
            "bluetooth not available" in m || "bluetooth is off" in m || "disabled" in m -> ConnectFailure.BLUETOOTH_OFF
            e is IOException -> ConnectFailure.ADAPTER_UNAVAILABLE
            else -> ConnectFailure.UNKNOWN
        }
    }
}
