package com.aicarcompanion.enet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LogEntry(val atMillis: Long, val message: String)

/**
 * Bounded diagnostic log. VINs and MAC addresses are masked before they are stored, so the
 * copied/exported text never contains a full vehicle identifier.
 */
class EnetLog(private val clock: () -> Long, private val capacity: Int = 500) {
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun add(message: String) {
        val entry = LogEntry(clock(), Redaction.redact(message))
        _entries.update { (it + entry).takeLast(capacity) }
    }

    fun clear() { _entries.value = emptyList() }

    fun export(header: String = "BMW ENET Connection Tester log (VINs and MAC addresses masked)"): String = buildString {
        appendLine(header)
        val start = _entries.value.firstOrNull()?.atMillis ?: 0
        _entries.value.forEach { appendLine("+%6d ms  %s".format(it.atMillis - start, it.message)) }
    }
}

object Redaction {
    private val VIN = Regex("\\b([A-HJ-NPR-Z0-9]{3})[A-HJ-NPR-Z0-9]{8}([A-HJ-NPR-Z0-9]{6})\\b")
    private val MAC = Regex("\\b([0-9A-Fa-f]{6})[0-9A-Fa-f]{6}\\b")
    private val MAC_COLON = Regex("\\b([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){2})(?::[0-9A-Fa-f]{2}){3}\\b")

    /** A VIN keeps its maker code and last 6 characters (enough to tell cars apart, not to identify one publicly). */
    fun maskVin(vin: String): String = if (vin.length == 17) vin.take(3) + "*".repeat(8) + vin.takeLast(6) else "*".repeat(vin.length)

    /** A MAC keeps the manufacturer prefix only. */
    fun maskMac(mac: String): String = if (mac.length == 12) mac.take(6) + "******" else "******"

    fun redact(text: String): String = text
        .replace(VIN) { m -> if (m.value.any { it in 'G'..'Z' }) "${m.groupValues[1]}********${m.groupValues[2]}" else m.value }
        .replace(MAC_COLON) { "${it.groupValues[1]}:**:**:**" }
        .replace(MAC) { "${it.groupValues[1]}******" }
}
