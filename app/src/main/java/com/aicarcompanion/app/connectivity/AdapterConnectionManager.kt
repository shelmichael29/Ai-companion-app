package com.aicarcompanion.app.connectivity

import com.aicarcompanion.app.diagnostics.bluetooth.AdapterTransport
import com.aicarcompanion.app.diagnostics.bluetooth.BluetoothAvailability
import com.aicarcompanion.app.diagnostics.bluetooth.ConnectFailure
import com.aicarcompanion.app.diagnostics.obd.AdapterAutoSelector
import com.aicarcompanion.app.diagnostics.obd.AdapterDevice
import com.aicarcompanion.app.diagnostics.obd.AdapterRadio
import com.aicarcompanion.app.diagnostics.obd.ObdConnection
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where the "connect my adapter" flow is, in steps a beginner understands. */
sealed interface AdapterSetupState {
    /** Plain words for Everyday Driver users: no addresses, protocols or error codes. */
    val everydayMessage: String

    data object Idle : AdapterSetupState { override val everydayMessage = "Tap Connect to find your car adapter." }
    data class Unavailable(val reason: ConnectFailure) : AdapterSetupState { override val everydayMessage get() = reason.everydayMessage }
    data object Searching : AdapterSetupState { override val everydayMessage = "Looking for your car adapter." }
    data class Found(val device: AdapterDevice) : AdapterSetupState { override val everydayMessage = "Found your adapter." }
    data class NeedsChoice(val candidates: List<AdapterDevice>) : AdapterSetupState {
        override val everydayMessage = "I found more than one adapter. Tap the one plugged into your car."
    }
    data class Connecting(val device: AdapterDevice, val attempt: Int, val maxAttempts: Int) : AdapterSetupState {
        override val everydayMessage = "Connecting to your vehicle."
    }
    data class Connected(val device: AdapterDevice) : AdapterSetupState { override val everydayMessage = "You're connected." }
    data class Failed(val reason: ConnectFailure, val detail: String?) : AdapterSetupState {
        override val everydayMessage get() = "I couldn't connect. Let's try again. ${reason.everydayMessage}"
    }
}

/**
 * Beginner-friendly adapter connection: checks Bluetooth, prefers the adapter that worked last
 * time, then a paired adapter that looks like an OBD-II one, then a short search for a nearby one.
 * It connects with a bounded number of attempts and remembers an adapter only after it really
 * connected.
 *
 * - **Separation:** this class only manages the adapter link. [ObdConnection] handles the
 *   ELM327 session and vehicle protocols; diagnostics, AI and UI stay separate.
 * - **Concurrency:** one flow at a time.
 * - **Cancel:** [cancel] stops a search or retries immediately.
 */
class AdapterConnectionManager(
    private val scope: CoroutineScope,
    private val transport: AdapterTransport,
    private val obd: ObdConnection,
    private val lastAdapterAddress: () -> String?,
    private val rememberAdapter: (AdapterDevice) -> Unit,
    private val log: ConnectionLog? = null,
    private val discoveryTimeoutMillis: Long = 12_000,
    /** Waits between connection attempts; attempts = this size + 1. */
    private val retryDelaysMillis: List<Long> = listOf(2_000, 5_000),
) {
    private val _state = MutableStateFlow<AdapterSetupState>(AdapterSetupState.Idle)
    val state: StateFlow<AdapterSetupState> = _state.asStateFlow()

    private var job: Job? = null

    /** One tap: find the right adapter and connect, asking only when the choice is ambiguous. */
    fun connectAutomatically() = start {
        if (!bluetoothReady()) return@start
        val paired = transport.pairedDevices()
        when (val choice = AdapterAutoSelector.choose(paired, lastAdapterAddress())) {
            is AdapterAutoSelector.Choice.Selected -> connectWithRetries(choice.device)
            is AdapterAutoSelector.Choice.NeedsUser -> _state.value = AdapterSetupState.NeedsChoice(choice.candidates)
            AdapterAutoSelector.Choice.NoneFound -> searchThenConnect()
        }
    }

    /** The user picked a specific adapter (paired or found nearby). */
    fun connect(device: AdapterDevice) = start {
        if (!bluetoothReady()) return@start
        connectWithRetries(device)
    }

    /** Looks for nearby adapters without connecting (DIY/Advanced "Search" button). */
    fun search(onFound: (List<AdapterDevice>) -> Unit) = start {
        if (!bluetoothReady() || !discoveryAllowed()) return@start
        _state.value = AdapterSetupState.Searching
        val found = discoverBounded()
        onFound(found)
        _state.value = if (found.isEmpty()) AdapterSetupState.Failed(ConnectFailure.NOT_FOUND, null) else AdapterSetupState.Idle
    }

    /**
     * App start or Bluetooth turned on: quietly reconnect to the adapter that last worked, if it's
     * paired and Bluetooth is ready. Never searches, never prompts. Returns true if it started.
     */
    fun reconnectLastAdapter(): Boolean {
        val address = lastAdapterAddress() ?: return false
        if (obd.state.value != ObdConnectionState.Disconnected && obd.state.value !is ObdConnectionState.Failed) return false
        if (transport.availability() != BluetoothAvailability.READY) return false
        val device = transport.pairedDevices().firstOrNull { it.address == address } ?: return false
        start { connectWithRetries(device) }
        return true
    }

    /** Stops searching and retrying, and disconnects. Nothing keeps running afterwards. */
    fun cancel() {
        job?.cancel()
        job = null
        obd.disconnect()
        _state.value = AdapterSetupState.Idle
        log?.record(LogArea.VEHICLE, "Connection cancelled by the user")
    }

    private fun start(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch { block() }
    }

    private fun bluetoothReady(): Boolean {
        val reason = when (transport.availability()) {
            BluetoothAvailability.READY -> return true
            BluetoothAvailability.DISABLED -> ConnectFailure.BLUETOOTH_OFF
            BluetoothAvailability.NO_PERMISSION -> ConnectFailure.PERMISSION_DENIED
            BluetoothAvailability.NOT_SUPPORTED -> ConnectFailure.NOT_SUPPORTED
        }
        _state.value = AdapterSetupState.Unavailable(reason)
        log?.record(LogArea.VEHICLE, "Bluetooth not ready", mapOf("reason" to reason))
        return false
    }

    private fun discoveryAllowed(): Boolean {
        if (transport.canDiscover()) return true
        _state.value = AdapterSetupState.Unavailable(ConnectFailure.PERMISSION_DENIED)
        log?.record(LogArea.VEHICLE, "Search not allowed", mapOf("reason" to ConnectFailure.PERMISSION_DENIED))
        return false
    }

    private suspend fun searchThenConnect() {
        if (!discoveryAllowed()) return
        _state.value = AdapterSetupState.Searching
        log?.record(LogArea.VEHICLE, "Searching for nearby adapters", mapOf("seconds" to discoveryTimeoutMillis / 1000))
        val found = discoverBounded().filter { it.looksLikeObdAdapter }
        when (found.size) {
            0 -> {
                _state.value = AdapterSetupState.Failed(ConnectFailure.NOT_FOUND, null)
                log?.record(LogArea.VEHICLE, "No adapter found nearby")
            }
            1 -> {
                _state.value = AdapterSetupState.Found(found.single())
                connectWithRetries(found.single())
            }
            else -> _state.value = AdapterSetupState.NeedsChoice(found)
        }
    }

    /**
     * Collects nearby Classic devices. The transport stops itself after [discoveryTimeoutMillis];
     * this adds a hard limit in case it doesn't, so a search can never run on and drain the battery.
     */
    private suspend fun discoverBounded(): List<AdapterDevice> {
        val found = mutableListOf<AdapterDevice>()
        withTimeoutOrNull(discoveryTimeoutMillis + DISCOVERY_GRACE_MILLIS) {
            transport.discover(discoveryTimeoutMillis).collect { found += it }
        }
        return found.filter { it.radio != AdapterRadio.LE_ONLY }.distinctBy { it.address }
    }

    /**
     * Connects with at most `retryDelaysMillis.size + 1` attempts. Only failures that another try
     * could fix (timeout, adapter briefly unreachable) are retried; Bluetooth off, missing permission
     * or an unsupported adapter stop at once.
     */
    private suspend fun connectWithRetries(device: AdapterDevice) {
        val max = retryDelaysMillis.size + 1
        for (attempt in 1..max) {
            _state.value = AdapterSetupState.Connecting(device, attempt, max)
            obd.connect(device)
            val outcome = obd.state.first { it is ObdConnectionState.Connected || it is ObdConnectionState.Failed }
            if (outcome is ObdConnectionState.Connected) {
                rememberAdapter(device) // only adapters that really connected are remembered
                _state.value = AdapterSetupState.Connected(device)
                return
            }
            val failed = outcome as ObdConnectionState.Failed
            if (!failed.reason.retryable || attempt == max) {
                _state.value = AdapterSetupState.Failed(failed.reason, failed.detail)
                return
            }
            log?.record(LogArea.VEHICLE, "Retrying connection", mapOf("attempt" to "${attempt + 1} of $max", "reason" to failed.reason))
            delay(retryDelaysMillis[attempt - 1])
        }
    }

    companion object {
        const val DISCOVERY_GRACE_MILLIS = 2_000L
    }
}
