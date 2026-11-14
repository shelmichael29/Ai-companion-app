package com.aicarcompanion.app.diagnostics.obd

import com.aicarcompanion.app.diagnostics.dtc.DtcDecoder

sealed interface ObdResponse {
    data class Data(val bytes: IntArray) : ObdResponse {
        override fun equals(other: Any?) = other is Data && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }

    /** The vehicle did not answer (ELM327 "NO DATA"); common for unsupported PIDs. */
    data object NoData : ObdResponse

    data class Error(val message: String) : ObdResponse
}

/**
 * Parses the text an ELM327-compatible adapter returns, assuming headers are off (`ATH0`)
 * and a single ECU responds. Multi-ECU / multi-frame responses are a planned extension.
 */
object Elm327ResponseParser {

    private val ERROR_MARKERS = listOf(
        "?", "UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "BUS INIT", "STOPPED", "ERROR",
    )

    fun parse(raw: String): ObdResponse {
        val cleaned = raw
            .replace(">", "")
            .replace("SEARCHING...", "", ignoreCase = true)
            .trim()
            .uppercase()

        if (cleaned.isEmpty()) return ObdResponse.Error("Empty response")
        if (cleaned.contains("NO DATA")) return ObdResponse.NoData
        ERROR_MARKERS.firstOrNull { cleaned.contains(it) }?.let { return ObdResponse.Error(cleaned) }

        val hex = cleaned.filterNot { it.isWhitespace() }
        if (hex.length % 2 != 0 || !hex.all { it in '0'..'9' || it in 'A'..'F' }) {
            return ObdResponse.Error("Unexpected response: $cleaned")
        }
        return ObdResponse.Data(IntArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16) })
    }

    /** Decodes a mode 01 response for [pid]; returns null if the response doesn't match. */
    fun decodePid(raw: String, pid: ObdPid): Double? {
        val bytes = (parse(raw) as? ObdResponse.Data)?.bytes ?: return null
        if (bytes.size < 2 + pid.dataBytes) return null
        if (bytes[0] != 0x41 || bytes[1] != pid.pid) return null
        return pid.decode(bytes.copyOfRange(2, 2 + pid.dataBytes))
    }

    /**
     * Decodes a mode 03 (stored), 07 (pending) or 0A (permanent) DTC response into codes
     * like "P0420". CAN responses carry a code-count byte after the mode byte; older
     * protocols don't, which we detect by the odd payload length.
     */
    fun decodeTroubleCodes(raw: String): List<String>? {
        val response = parse(raw)
        if (response is ObdResponse.NoData) return emptyList()
        val bytes = (response as? ObdResponse.Data)?.bytes ?: return null
        if (bytes.isEmpty() || bytes[0] !in setOf(0x43, 0x47, 0x4A)) return null

        var payload = bytes.copyOfRange(1, bytes.size)
        if (payload.size % 2 == 1) payload = payload.copyOfRange(1, payload.size)
        return DtcDecoder.decodePayload(payload)
    }
}
