package com.aicarcompanion.app.diagnostics.obd

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** A bidirectional text pipe to an ELM327-compatible adapter (Bluetooth SPP, BLE, Wi-Fi…). */
interface ElmLink {
    /** @throws IOException if the link is broken. */
    suspend fun write(text: String)

    /** Reads until the adapter's `>` prompt. @throws ObdTimeoutException, IOException. */
    suspend fun readUntilPrompt(timeoutMillis: Long): String

    fun close()
}

class ObdTimeoutException(message: String) : IOException(message)

/** What the adapter and vehicle reported during setup. */
data class ElmInitResult(
    val adapterVersion: String?,
    val vehicle: VehicleProbe?,
)

data class VehicleProbe(
    val protocolNumber: String?,
    val protocolName: String?,
    val supportedPids: Set<Int>,
) {
    val isCan: Boolean? get() = protocolNumber?.lastOrNull()?.let { it in "6789ABC" }
}

/**
 * Serializes commands to one adapter, enforces [ReadOnlyCommandPolicy], and performs the
 * standard ELM327 initialization sequence. Tolerates clones that reject optional commands.
 */
class Elm327Session(private val link: ElmLink) {
    private val mutex = Mutex()

    suspend fun execute(command: String, timeoutMillis: Long = DEFAULT_TIMEOUT): String = mutex.withLock {
        ReadOnlyCommandPolicy.requireAllowed(command)
        val normalized = ReadOnlyCommandPolicy.normalize(command)
        link.write("$normalized\r")
        val raw = link.readUntilPrompt(timeoutMillis)
        clean(raw, normalized)
    }

    suspend fun initialize(): ElmInitResult {
        val reset = execute("ATZ", RESET_TIMEOUT)
        val version = Elm327ResponseParser.lines(reset).firstOrNull { it.startsWith("ELM") || it.contains("V1.") }
        execute("ATE0")                      // echo off (required)
        optional("ATL0")                     // no linefeeds
        optional("ATS1")                     // keep spaces; parser handles both
        optional("ATH0")                     // headers off
        optional("ATAT1")                    // adaptive timing
        optional("ATSP0")                    // automatic protocol search
        return ElmInitResult(version?.let(::tidyVersion), probeVehicle())
    }

    /** Asks the vehicle which PIDs it supports. Null when the vehicle doesn't respond (e.g. ignition off). */
    suspend fun probeVehicle(): VehicleProbe? {
        val first = execute("0100", SEARCH_TIMEOUT)
        val supported = Elm327ResponseParser.decodeSupportedPids(first, 0x00).toMutableSet()
        if (supported.isEmpty()) return null
        var base = 0x20
        while (base <= 0xA0 && base in supported) {
            supported += Elm327ResponseParser.decodeSupportedPids(execute("01%02X".format(base)), base)
            base += 0x20
        }
        val dpn = runCatching { Elm327ResponseParser.lines(execute("ATDPN")).firstOrNull() }.getOrNull()
        val number = dpn?.removePrefix("A")?.takeIf { it.length == 1 }
        return VehicleProbe(number, number?.let(PROTOCOL_NAMES::get), supported)
    }

    private suspend fun optional(command: String) {
        runCatching { execute(command) }.onFailure { if (it is IOException && it !is ObdTimeoutException) throw it }
    }

    private fun tidyVersion(v: String) =
        v.replace("ELM327", "ELM327 ").replace(Regex("\\s+"), " ").replace(Regex("V(\\d)"), "v$1").trim()

    companion object {
        const val DEFAULT_TIMEOUT = 3_000L
        const val RESET_TIMEOUT = 5_000L
        const val SEARCH_TIMEOUT = 12_000L

        val PROTOCOL_NAMES = mapOf(
            "1" to "SAE J1850 PWM", "2" to "SAE J1850 VPW", "3" to "ISO 9141-2",
            "4" to "ISO 14230-4 KWP (5-baud init)", "5" to "ISO 14230-4 KWP (fast init)",
            "6" to "ISO 15765-4 CAN (11-bit, 500 kbaud)", "7" to "ISO 15765-4 CAN (29-bit, 500 kbaud)",
            "8" to "ISO 15765-4 CAN (11-bit, 250 kbaud)", "9" to "ISO 15765-4 CAN (29-bit, 250 kbaud)",
            "A" to "SAE J1939 CAN", "B" to "User CAN 1", "C" to "User CAN 2",
        )

        /** Removes the prompt and any echoed command line. */
        fun clean(raw: String, command: String): String = raw
            .replace(">", "")
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && ReadOnlyCommandPolicy.normalize(it) != command }
            .joinToString("\r")
    }
}
