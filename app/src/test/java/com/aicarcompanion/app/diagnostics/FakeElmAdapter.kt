package com.aicarcompanion.app.diagnostics

import com.aicarcompanion.app.diagnostics.obd.ElmLink
import java.io.IOException

/**
 * Scripted ELM327 adapter. Responses mimic a typical v1.5 clone on a CAN vehicle such as a
 * 2011 BMW 528i (protocol 6). Unknown commands get "?" like a real adapter.
 */
class FakeElmAdapter(
    overrides: Map<String, String> = emptyMap(),
    var vehicleOn: Boolean = true,
) : ElmLink {
    val written = mutableListOf<String>()
    var failAfterWrites: Int? = null
    var closed = false
    /** Command -> how many times it should time out before answering. */
    val timeouts = mutableMapOf<String, Int>()

    val responses = mutableMapOf(
        "ATZ" to "\r\rELM327 v1.5",
        "ATE0" to "ATE0\rOK", // echo still on for this one
        "ATL0" to "OK", "ATS1" to "OK", "ATH0" to "OK", "ATAT1" to "?", "ATSP0" to "OK",
        "ATDPN" to "A6",
        "ATRV" to "12.4V",
        // Engine + transmission both answer; first supports 01-20 incl. 0C,0D,05; second fewer.
        "0100" to "SEARCHING...\r41 00 BE 3F A8 13\r41 00 98 18 00 01",
        "0120" to "41 20 80 00 00 01",
        "0140" to "41 40 40 00 00 00", // 0x42 supported
        "010C" to "41 0C 0C 80", // 800 rpm
        "010D" to "41 0D 00",
        "0105" to "41 05 82", // 90 °C
        "0142" to "41 42 37 14", // 14.1 V
        "0104" to "41 04 33",
        "0111" to "41 11 26",
        "010F" to "41 0F 46",
        "03" to "43 01 01 71", // CAN: count=1, P0171
        "07" to "43 00".replace("43", "47"),
    ).apply { putAll(overrides) }

    private var pending: String? = null

    override suspend fun write(text: String) {
        if (closed) throw IOException("closed")
        failAfterWrites?.let { if (written.size >= it) throw IOException("link lost") }
        val cmd = text.trim()
        written += cmd
        pending = when {
            !vehicleOn && cmd.matches(Regex("^0[1-9A].*")) -> "SEARCHING...\rUNABLE TO CONNECT"
            else -> responses[cmd] ?: "?"
        }
    }

    override suspend fun readUntilPrompt(timeoutMillis: Long): String {
        val last = written.lastOrNull()
        val left = last?.let { timeouts[it] } ?: 0
        if (left > 0) {
            timeouts[last!!] = left - 1
            pending = null
            throw com.aicarcompanion.app.diagnostics.obd.ObdTimeoutException("timeout")
        }
        val r = pending ?: throw IOException("nothing pending")
        pending = null
        return "$r\r\r>"
    }

    override fun close() { closed = true }
}
