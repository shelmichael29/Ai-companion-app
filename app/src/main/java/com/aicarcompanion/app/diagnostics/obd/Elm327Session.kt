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
 * Timeouts and retries come from [ObdConnectionConfig]; every exchange is reported to [observer]
 * (connection statistics and the optional redacted traffic log).
 */
/** A command was not sent because the caller asked to skip it once its turn came. */
class CommandSkippedException : Exception("Command skipped")

class Elm327Session(
    private val link: ElmLink,
    private val observer: ElmTrafficObserver? = null,
    private val onRetry: () -> Unit = {},
) {
    private val mutex = Mutex()

    /** Default wait for an answer, from the connection settings. */
    @Volatile var timeoutMillis: Long = DEFAULT_TIMEOUT

    /** Extra attempts after a timeout, from the connection settings. */
    @Volatile var retries: Int = 0

    var capabilities = AdapterCapabilities()
        private set

    /**
     * [skipIf] is checked after this command's turn on the adapter begins; if it is true nothing is
     * sent and [CommandSkippedException] is thrown (used to hold live polling off during a clear).
     */
    suspend fun execute(
        command: String,
        timeoutMillis: Long = this.timeoutMillis,
        skipIf: () -> Boolean = { false },
    ): String = mutex.withLock {
        ReadOnlyCommandPolicy.requireAllowed(command)
        if (skipIf()) throw CommandSkippedException()
        val normalized = ReadOnlyCommandPolicy.normalize(command)
        // Resets are never repeated automatically; everything else may retry after a timeout.
        val attempts = if (normalized == "ATZ" || normalized == "ATWS" || normalized == "ATD") 1 else 1 + retries.coerceAtLeast(0)
        var lastTimeout: ObdTimeoutException? = null
        repeat(attempts) { attempt ->
            if (attempt > 0) onRetry()
            val started = System.nanoTime()
            try {
                link.write("$normalized\r")
                val raw = link.readUntilPrompt(timeoutMillis)
                val cleaned = clean(raw, normalized)
                observer?.onExchange(normalized, cleaned, elapsed(started), outcomeOf(cleaned))
                return@withLock cleaned
            } catch (e: ObdTimeoutException) {
                observer?.onExchange(normalized, null, elapsed(started), ExchangeOutcome.TIMEOUT)
                lastTimeout = e
            } catch (e: IOException) {
                observer?.onExchange(normalized, null, elapsed(started), ExchangeOutcome.LINK_LOST)
                throw e
            }
        }
        throw lastTimeout ?: ObdTimeoutException("No response")
    }

    /**
     * Sends Mode 04 (clear trouble codes). The ONLY vehicle-modifying request this class can send,
     * and only with a [ClearCodesPermit] from [VehicleWriteGate]. Never retried: a repeat after a
     * timeout could clear codes twice without the user knowing. Returns the cleaned response.
     */
    suspend fun sendClearCodes(permit: ClearCodesPermit): String? = mutex.withLock {
        if (permit.vehicleId.isBlank()) return@withLock null // nothing is sent without a vehicle
        val started = System.nanoTime()
        try {
            link.write("04\r")
            val cleaned = clean(link.readUntilPrompt(CLEAR_TIMEOUT), "04")
            observer?.onExchange("04", cleaned, elapsed(started), outcomeOf(cleaned))
            cleaned
        } catch (e: ObdTimeoutException) {
            observer?.onExchange("04", null, elapsed(started), ExchangeOutcome.TIMEOUT)
            throw e
        } catch (e: IOException) {
            observer?.onExchange("04", null, elapsed(started), ExchangeOutcome.LINK_LOST)
            throw e
        }
    }

    /** Initializes with the recommended, fully automatic configuration. */
    suspend fun initialize(): ElmInitResult = initialize(ObdConnectionConfig.RECOMMENDED)

    /**
     * Resets and configures the adapter. With the recommended config this sends exactly:
     * ATZ, ATE0, ATL0, ATS1, ATH0, ATAT1, ATSP0. Options the adapter rejects fall back to the
     * automatic behavior and are recorded in [capabilities] so the UI only offers what works.
     */
    /** Resets and configures the adapter (no vehicle traffic). Returns the adapter version, if reported. */
    suspend fun initializeAdapter(config: ObdConnectionConfig): String? {
        timeoutMillis = config.responseTimeoutMillis.toLong()
        retries = config.retries
        val reset = execute("ATZ", RESET_TIMEOUT)
        val version = Elm327ResponseParser.lines(reset).firstOrNull { it.startsWith("ELM") || it.contains("V1.") }
        execute("ATE0")                      // echo off (required)
        optional("ATL0")                     // no linefeeds
        var caps = AdapterCapabilities()
        if (config.compactResponses) {
            val ok = optional("ATS0")        // no spaces; parser handles both
            caps = caps.copy(compactResponses = ok)
            if (ok != true) optional("ATS1")
        } else {
            optional("ATS1")                 // keep spaces; parser handles both
        }
        optional("ATH0")                     // headers off (required by the parser)
        when (config.adaptiveTiming) {
            AdaptiveTiming.NORMAL -> caps = caps.copy(adaptiveTiming = optional("ATAT1"))
            AdaptiveTiming.AGGRESSIVE -> {
                val ok = optional("ATAT2")
                caps = caps.copy(aggressiveTiming = ok)
                if (ok != true) caps = caps.copy(adaptiveTiming = optional("ATAT1"))
            }
            AdaptiveTiming.OFF -> {
                caps = caps.copy(adaptiveTiming = optional("ATAT0"))
                caps = caps.copy(fixedTimeout = optional(config.adapterTimeoutCommand))
            }
        }
        val protocolOk = optional(config.protocolCommand)
        if (config.protocol != ObdProtocol.AUTO) {
            caps = caps.copy(manualProtocol = protocolOk)
            if (protocolOk != true) optional("ATSP0") // never leave the adapter without a working protocol
        }
        capabilities = caps
        return version?.let(::tidyVersion)
    }

    /**
     * Resets and configures the adapter, then asks the vehicle what it supports.
     * Equivalent to [initializeAdapter] followed by [probeVehicle].
     */
    suspend fun initialize(config: ObdConnectionConfig): ElmInitResult {
        val version = initializeAdapter(config)
        return ElmInitResult(version, probeVehicle())
    }

    /**
     * Selects an OBD-II protocol (ATSPn). Returns false if the adapter rejects it. Only used in
     * automatic mode, after the adapter's own search found nothing.
     */
    suspend fun selectProtocol(protocol: ObdProtocol): Boolean = optional("ATSP${protocol.code}") == true

    /** Reads the adapter's description and port voltage. Both are optional on clones. */
    suspend fun identify(): Pair<String?, Double?> {
        val desc = runCatching { execute("AT@1") }.getOrNull()
            ?.let { Elm327ResponseParser.lines(it).firstOrNull() }?.takeIf { it != "?" }
        val volts = runCatching { execute("ATRV") }.getOrNull()?.let(Elm327ResponseParser::decodeAdapterVoltage)
        capabilities = capabilities.copy(description = desc != null, voltage = volts != null)
        return desc to volts
    }

    /** Asks the vehicle which PIDs it supports. Null when the vehicle doesn't respond (e.g. ignition off). */
    suspend fun probeVehicle(onVehicleAnswered: () -> Unit = {}): VehicleProbe? {
        // A timeout here means the car didn't answer (ignition off, slow protocol search): the
        // adapter itself is fine, so report "vehicle not responding" instead of failing the whole
        // connection. A lost link (other IOException) still propagates.
        val first = try {
            execute("0100", maxOf(SEARCH_TIMEOUT, timeoutMillis))
        } catch (e: ObdTimeoutException) {
            return null
        }
        val supported = Elm327ResponseParser.decodeSupportedPids(first, 0x00).toMutableSet()
        if (supported.isEmpty()) return null
        onVehicleAnswered()
        var base = 0x20
        while (base <= 0xA0 && base in supported) {
            // A timed-out extended range just means those PIDs aren't listed; keep what we have.
            val more = try { execute("01%02X".format(base)) } catch (e: ObdTimeoutException) { break }
            supported += Elm327ResponseParser.decodeSupportedPids(more, base)
            base += 0x20
        }
        val dpn = runCatching { Elm327ResponseParser.lines(execute("ATDPN")).firstOrNull() }.getOrNull()
        val number = dpn?.removePrefix("A")?.takeIf { it.length == 1 }
        return VehicleProbe(number, number?.let(PROTOCOL_NAMES::get), supported)
    }

    /** true = accepted, false = adapter answered "?", null = no answer in time. */
    private suspend fun optional(command: String): Boolean? = try {
        !isUnsupported(execute(command))
    } catch (e: ObdTimeoutException) {
        null
    }

    private fun tidyVersion(v: String) =
        v.replace("ELM327", "ELM327 ").replace(Regex("\\s+"), " ").replace(Regex("V(\\d)"), "v$1").trim()

    companion object {
        const val DEFAULT_TIMEOUT = 3_000L
        const val RESET_TIMEOUT = 5_000L
        const val SEARCH_TIMEOUT = 12_000L
        const val CLEAR_TIMEOUT = 8_000L

        val PROTOCOL_NAMES = mapOf(
            "1" to "SAE J1850 PWM", "2" to "SAE J1850 VPW", "3" to "ISO 9141-2",
            "4" to "ISO 14230-4 KWP (5-baud init)", "5" to "ISO 14230-4 KWP (fast init)",
            "6" to "ISO 15765-4 CAN (11-bit, 500 kbaud)", "7" to "ISO 15765-4 CAN (29-bit, 500 kbaud)",
            "8" to "ISO 15765-4 CAN (11-bit, 250 kbaud)", "9" to "ISO 15765-4 CAN (29-bit, 250 kbaud)",
            "A" to "SAE J1939 CAN", "B" to "User CAN 1", "C" to "User CAN 2",
        )

        private fun elapsed(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000

        fun isUnsupported(cleaned: String) = Elm327ResponseParser.lines(cleaned).any { it == "?" }

        fun outcomeOf(cleaned: String): ExchangeOutcome {
            val lines = Elm327ResponseParser.lines(cleaned)
            return when {
                lines.any { it == "?" } -> ExchangeOutcome.UNSUPPORTED
                lines.any { it.contains("NO DATA") } -> ExchangeOutcome.NO_DATA
                lines.any { l -> listOf("UNABLE TO CONNECT", "ERROR", "STOPPED", "BUFFER FULL").any { l.contains(it) } } -> ExchangeOutcome.ERROR
                else -> ExchangeOutcome.OK
            }
        }

        /** Removes the prompt and any echoed command line. */
        fun clean(raw: String, command: String): String = raw
            .replace(">", "")
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && ReadOnlyCommandPolicy.normalize(it) != command }
            .joinToString("\r")
    }
}
