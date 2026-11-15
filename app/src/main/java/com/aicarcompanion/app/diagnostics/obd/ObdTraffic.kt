package com.aicarcompanion.app.diagnostics.obd

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ExchangeOutcome { OK, UNSUPPORTED, NO_DATA, ERROR, TIMEOUT, LINK_LOST }

/** Receives every command/response exchange with the adapter. */
fun interface ElmTrafficObserver {
    fun onExchange(command: String, response: String?, elapsedMillis: Long, outcome: ExchangeOutcome)
}

/** Running connection-quality numbers for the current session. */
data class ConnectionStats(
    val requests: Int = 0,
    val timeouts: Int = 0,
    val errors: Int = 0,
    val retries: Int = 0,
    val reconnects: Int = 0,
    val averageLatencyMillis: Long? = null,
    val maxLatencyMillis: Long? = null,
) {
    val timeoutRate: Double get() = if (requests == 0) 0.0 else timeouts.toDouble() / requests
}

class ConnectionStatsTracker : ElmTrafficObserver {
    private val _stats = MutableStateFlow(ConnectionStats())
    val stats: StateFlow<ConnectionStats> = _stats.asStateFlow()
    private var latencyTotal = 0L
    private var latencyCount = 0

    override fun onExchange(command: String, response: String?, elapsedMillis: Long, outcome: ExchangeOutcome) {
        _stats.update { s ->
            val ok = outcome != ExchangeOutcome.TIMEOUT && outcome != ExchangeOutcome.LINK_LOST
            if (ok) { latencyTotal += elapsedMillis; latencyCount++ }
            s.copy(
                requests = s.requests + 1,
                timeouts = s.timeouts + if (outcome == ExchangeOutcome.TIMEOUT) 1 else 0,
                errors = s.errors + if (outcome == ExchangeOutcome.ERROR || outcome == ExchangeOutcome.LINK_LOST) 1 else 0,
                averageLatencyMillis = if (latencyCount == 0) null else latencyTotal / latencyCount,
                maxLatencyMillis = if (ok) maxOf(s.maxLatencyMillis ?: 0, elapsedMillis) else s.maxLatencyMillis,
            )
        }
    }

    fun onRetry() = _stats.update { it.copy(retries = it.retries + 1) }
    fun onReconnect() = _stats.update { it.copy(reconnects = it.reconnects + 1) }

    fun reset() {
        latencyTotal = 0; latencyCount = 0
        _stats.value = ConnectionStats()
    }
}

data class TrafficLogEntry(val atMillis: Long, val command: String, val response: String, val elapsedMillis: Long, val outcome: ExchangeOutcome)

/**
 * Privacy-conscious, in-memory record of adapter traffic for troubleshooting.
 * - Off unless enabled at the Advanced level; never written to disk; cleared when turned off.
 * - Vehicle identifiers are redacted: responses to mode 09 information requests (VIN,
 *   calibration IDs, CVNs, ECU name) and anything shaped like a VIN.
 * - Adapter Bluetooth addresses and device names are never logged.
 */
class TrafficLog(private val capacity: Int = 300, private val clock: () -> Long = System::currentTimeMillis) : ElmTrafficObserver {
    @Volatile var enabled: Boolean = false
        set(value) { field = value; if (!value) clear() }

    private val _entries = MutableStateFlow<List<TrafficLogEntry>>(emptyList())
    val entries: StateFlow<List<TrafficLogEntry>> = _entries.asStateFlow()

    override fun onExchange(command: String, response: String?, elapsedMillis: Long, outcome: ExchangeOutcome) {
        if (!enabled) return
        val entry = TrafficLogEntry(clock(), command, redact(command, response.orEmpty()), elapsedMillis, outcome)
        _entries.update { (it + entry).takeLast(capacity) }
    }

    fun clear() { _entries.value = emptyList() }

    /** Plain-text export for sharing with support. Already redacted. */
    fun export(): String = buildString {
        appendLine("AI Car Companion OBD-II log (vehicle identifiers redacted)")
        val start = _entries.value.firstOrNull()?.atMillis ?: 0
        _entries.value.forEach { e ->
            appendLine("+${e.atMillis - start}ms  ${e.command} -> ${e.response.replace('\r', ' ')}  [${e.outcome}, ${e.elapsedMillis}ms]")
        }
    }

    companion object {
        const val REDACTED = "[redacted: vehicle identifier]"
        private val VIN_LIKE = Regex("\\b[A-HJ-NPR-Z0-9]{17}\\b")
        private val NON_HEX_LETTER = Regex("[G-HJ-NPR-Z]")
        private val IDENTIFYING_MODE_09 = Regex("^09(0[1-9A-F]|[1-9A-F][0-9A-F])$")

        fun redact(command: String, response: String): String {
            val c = ReadOnlyCommandPolicy.normalize(command)
            // 0900 only lists which info PIDs exist; everything else in mode 09 identifies the car.
            if (IDENTIFYING_MODE_09.matches(c)) return REDACTED
            return VIN_LIKE.replace(response.take(200)) { if (NON_HEX_LETTER.containsMatchIn(it.value)) REDACTED else it.value }
        }
    }
}

/** Fans one exchange out to several observers. */
class TrafficObservers(private vararg val observers: ElmTrafficObserver) : ElmTrafficObserver {
    override fun onExchange(command: String, response: String?, elapsedMillis: Long, outcome: ExchangeOutcome) =
        observers.forEach { it.onExchange(command, response, elapsedMillis, outcome) }
}

/** One step of the connection self-test. */
data class DiagnosticStep(val title: String, val result: Result, val detail: String) {
    enum class Result(val label: String) { PASS("OK"), WARN("Check"), FAIL("Problem"), SKIPPED("Skipped") }
}

data class ConnectionDiagnosticReport(val steps: List<DiagnosticStep>) {
    val overall: DiagnosticStep.Result
        get() = when {
            steps.any { it.result == DiagnosticStep.Result.FAIL } -> DiagnosticStep.Result.FAIL
            steps.any { it.result == DiagnosticStep.Result.WARN } -> DiagnosticStep.Result.WARN
            else -> DiagnosticStep.Result.PASS
        }
}

/** Plain-language troubleshooting for the current connection state (DIY level and up). */
object ConnectionTroubleshooter {
    fun tips(state: ObdConnectionState, stats: ConnectionStats, info: AdapterInfo?): List<String> {
        val tips = mutableListOf<String>()
        when (state) {
            ObdConnectionState.Disconnected, is ObdConnectionState.Failed -> {
                tips += "Make sure the adapter is pushed fully into the OBD-II port (under the dashboard, driver's side) and its light is on."
                tips += "Turn the ignition to ON (engine running or accessory) so the port has power."
                tips += "Close other OBD apps; most adapters accept only one connection at a time."
                tips += "If it still fails, unpair and re-pair the adapter in Android Bluetooth settings (PIN is usually 1234 or 0000)."
            }
            is ObdConnectionState.Connected -> if (state.vehicleProtocol == null) {
                tips += "The adapter is fine but the car isn't answering. Turn the ignition fully on, then tap \"Check vehicle again\"."
                tips += "Some cars need the engine running before they respond."
            }
            is ObdConnectionState.Reconnecting -> tips += "The Bluetooth link dropped. Keep the phone near the adapter; it will reconnect by itself."
            else -> Unit
        }
        if (stats.requests >= 10 && stats.timeoutRate > 0.2) {
            tips += "Many requests are timing out. Fewer live readings (Everyday level) or a better-quality adapter usually helps."
        }
        info?.voltage?.let { if (it < 11.8) tips += "Battery voltage at the port is low (%.1f V). Readings can be unreliable until the battery is charged.".format(it) }
        if (info?.likelyClone == true) tips += "This adapter reports firmware \"v2.1\", which usually means a low-cost clone. If connections are unreliable, a known-brand adapter helps."
        return tips
    }
}
