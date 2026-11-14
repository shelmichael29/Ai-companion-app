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
 * Parses text returned by ELM327-compatible adapters with headers off (`ATH0`).
 *
 * Handles: echo/prompt noise, "SEARCHING...", one line per responding ECU (e.g. engine and
 * transmission both answering `0100`), and ISO-TP multi-frame CAN responses, which the
 * adapter prints as a byte-count line followed by "0:", "1:", … frame lines.
 */
object Elm327ResponseParser {

    private val ERROR_MARKERS = listOf(
        "?", "UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "BUS INIT", "BUS BUSY", "FB ERROR",
        "DATA ERROR", "BUFFER FULL", "STOPPED", "ERROR", "LV RESET", "ACT ALERT",
    )
    private val FRAME_LINE = Regex("""^([0-9A-F]):\s*(.*)$""")
    private val LENGTH_LINE = Regex("""^[0-9A-F]{3}$""")
    private val HEX_LINE = Regex("""^[0-9A-F ]+$""")

    /** Splits a raw response into cleaned, uppercase lines without noise. */
    fun lines(raw: String): List<String> = raw
        .replace(">", "\r")
        .uppercase()
        .split('\r', '\n')
        .map { it.trim() }
        .filter {
            it.isNotEmpty() && !it.startsWith("SEARCHING") && it != "OK" &&
                !(it.startsWith("BUS INIT") && it.endsWith("OK"))
        }

    /** All messages in the response (one per ECU, multi-frame messages reassembled), or a status. */
    fun messages(raw: String): Result {
        val lines = lines(raw)
        if (lines.isEmpty()) return Result.Status(ObdResponse.Error("Empty response"))
        if (lines.any { it.contains("NO DATA") } && lines.none { HEX_LINE.matches(it) && it.length >= 4 }) {
            return Result.Status(ObdResponse.NoData)
        }
        ERROR_MARKERS.firstOrNull { marker -> lines.any { it.contains(marker) } }?.let {
            if (lines.none { HEX_LINE.matches(it) && it.length >= 4 }) {
                return Result.Status(ObdResponse.Error(lines.joinToString(" ")))
            }
        }

        val messages = mutableListOf<IntArray>()
        var declaredLength: Int? = null
        val frameBytes = mutableListOf<Int>()
        fun flushFrames() {
            if (frameBytes.isEmpty()) return
            val len = declaredLength ?: frameBytes.size
            messages += frameBytes.take(len).toIntArray()
            frameBytes.clear()
            declaredLength = null
        }

        for (line in lines) {
            val frame = FRAME_LINE.find(line)
            when {
                LENGTH_LINE.matches(line) -> { flushFrames(); declaredLength = line.toInt(16) }
                frame != null -> {
                    if (frame.groupValues[1] == "0") { val keep = declaredLength; flushFrames(); declaredLength = keep }
                    frameBytes += hexBytes(frame.groupValues[2]) ?: return malformed(line)
                }
                HEX_LINE.matches(line) -> { flushFrames(); messages += (hexBytes(line) ?: return malformed(line)).toIntArray() }
                line.contains("NO DATA") -> Unit // another ECU answered; ignore this one
                else -> return malformed(line)
            }
        }
        flushFrames()
        return if (messages.isEmpty()) Result.Status(ObdResponse.NoData) else Result.Messages(messages)
    }

    sealed interface Result {
        data class Messages(val messages: List<IntArray>) : Result
        data class Status(val response: ObdResponse) : Result
    }

    private fun malformed(line: String) = Result.Status(ObdResponse.Error("Unexpected response: $line"))

    private fun hexBytes(text: String): List<Int>? {
        val hex = text.filterNot { it.isWhitespace() }
        if (hex.length % 2 != 0 || !hex.all { it in '0'..'9' || it in 'A'..'F' }) return null
        return (0 until hex.length / 2).map { hex.substring(it * 2, it * 2 + 2).toInt(16) }
    }

    /** Single-message view kept for simple callers: the first message, or the status. */
    fun parse(raw: String): ObdResponse = when (val r = messages(raw)) {
        is Result.Status -> r.response
        is Result.Messages -> ObdResponse.Data(r.messages.first())
    }

    /** Decodes a mode 01 response for [pid] from the first ECU that answered it. */
    fun decodePid(raw: String, pid: ObdPid): Double? {
        val msgs = (messages(raw) as? Result.Messages)?.messages ?: return null
        val match = msgs.firstOrNull { it.size >= 2 + pid.dataBytes && it[0] == 0x41 && it[1] == pid.pid } ?: return null
        return pid.decode(match.copyOfRange(2, 2 + pid.dataBytes))
    }

    /**
     * Decodes a mode 03 (stored), 07 (pending) or 0A (permanent) response into codes like
     * "P0420", merging all responding ECUs. CAN responses carry a code-count byte after the
     * mode byte; pass [isCan] when the protocol is known, otherwise it is inferred.
     */
    fun decodeTroubleCodes(raw: String, isCan: Boolean? = null): List<String>? {
        val msgs = when (val r = messages(raw)) {
            is Result.Status -> return if (r.response is ObdResponse.NoData) emptyList() else null
            is Result.Messages -> r.messages
        }
        val relevant = msgs.filter { it.isNotEmpty() && it[0] in setOf(0x43, 0x47, 0x4A) }
        if (relevant.isEmpty()) return null
        return relevant.flatMap { msg ->
            var payload = msg.copyOfRange(1, msg.size)
            val hasCount = isCan ?: (payload.size % 2 == 1)
            if (hasCount && payload.isNotEmpty()) payload = payload.copyOfRange(1, payload.size)
            DtcDecoder.decodePayload(payload)
        }.distinct()
    }

    /**
     * Decodes a "PIDs supported" response (01 00 / 01 20 / 01 40 …), OR-ing all ECUs.
     * Returns the set of supported PID numbers.
     */
    fun decodeSupportedPids(raw: String, basePid: Int): Set<Int> {
        val msgs = (messages(raw) as? Result.Messages)?.messages ?: return emptySet()
        val result = mutableSetOf<Int>()
        msgs.filter { it.size >= 6 && it[0] == 0x41 && it[1] == basePid }.forEach { m ->
            val bits = (m[2].toLong() shl 24) or (m[3].toLong() shl 16) or (m[4].toLong() shl 8) or m[5].toLong()
            for (i in 0 until 32) if (bits and (1L shl (31 - i)) != 0L) result += basePid + i + 1
        }
        return result
    }

    /** Parses the adapter's own voltage reading (`ATRV`), e.g. "12.6V". */
    fun decodeAdapterVoltage(raw: String): Double? =
        lines(raw).firstNotNullOfOrNull { Regex("""^(\d{1,2}(?:\.\d)?)V$""").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
}
