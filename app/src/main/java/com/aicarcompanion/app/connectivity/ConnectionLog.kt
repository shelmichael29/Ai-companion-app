package com.aicarcompanion.app.connectivity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Which system an event belongs to. Each is diagnosed separately. */
enum class LogArea(val label: String) { VEHICLE("Vehicle"), INTERNET("Internet"), AI("AI") }

/** One structured diagnostic event: a short name plus key/value details. Never conversation text. */
data class ConnectionEvent(val at: Instant, val area: LogArea, val event: String, val details: Map<String, String> = emptyMap())

/**
 * In-memory, size-limited diagnostic log for the vehicle connection, the internet connection and
 * the AI service. Shown in Settings → Connection status (technical log for DIY/Advanced users).
 *
 * Privacy: details are redacted when recorded. VINs and Bluetooth/MAC addresses are masked,
 * and anything that looks like a key or token is removed. Callers must never pass API keys, tokens
 * or what the user said; this class is a second line of defense, not the first.
 * Nothing is written to disk or sent anywhere.
 */
class ConnectionLog(private val capacity: Int = 300, private val now: () -> Instant = Instant::now) {
    private val _events = MutableStateFlow<List<ConnectionEvent>>(emptyList())
    val events: StateFlow<List<ConnectionEvent>> = _events.asStateFlow()

    fun record(area: LogArea, event: String, details: Map<String, Any?> = emptyMap()) {
        val clean = details.filterValues { it != null }.mapValues { (_, v) -> redact(v.toString()) }
        val entry = ConnectionEvent(now(), area, redact(event), clean)
        _events.update { (it + entry).takeLast(capacity) }
    }

    fun clear() { _events.value = emptyList() }

    /** Most recent event for an area, if any. */
    fun latest(area: LogArea): ConnectionEvent? = _events.value.lastOrNull { it.area == area }

    /** Plain-text export for the Copy button. Already redacted. */
    fun export(zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        appendLine("Penelope connection log (VINs, hardware addresses and keys removed)")
        _events.value.forEach { e ->
            append(TIME.format(e.at.atZone(zone))).append("  ").append(e.area.label.padEnd(8)).append(e.event)
            if (e.details.isNotEmpty()) append("  ").append(e.details.entries.joinToString(", ") { "${it.key}=${it.value}" })
            appendLine()
        }
    }

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val VIN = Regex("\\b[A-HJ-NPR-Z0-9]{17}\\b")
        private val MAC = Regex("\\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\\b")
        private val KEY_LIKE = Regex("\\b(?:AIza[0-9A-Za-z_\\-]{20,}|ya29\\.[0-9A-Za-z_\\-]+|eyJ[0-9A-Za-z_\\-]{10,}\\.[0-9A-Za-z_\\-.]+)")

        /** Masks VINs (keeping the last 4), hardware addresses and token-like strings. */
        fun redact(text: String): String = text
            .let { KEY_LIKE.replace(it, "[removed]") }
            .let { MAC.replace(it) { m -> "**:**:**:**:" + m.value.takeLast(5) } }
            .let { VIN.replace(it) { m -> if (m.value.any { c -> c.isLetter() } && m.value.any { c -> c.isDigit() }) "*************" + m.value.takeLast(4) else m.value } }
    }
}
