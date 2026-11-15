package com.aicarcompanion.app.diagnostics.obd

import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** OBD-II protocols an ELM327 can be told to use. User CAN and J1939 are excluded: not used by passenger cars. */
enum class ObdProtocol(val code: String, val label: String) {
    AUTO("0", "Automatic (recommended)"),
    J1850_PWM("1", "SAE J1850 PWM (older Ford)"),
    J1850_VPW("2", "SAE J1850 VPW (older GM)"),
    ISO_9141_2("3", "ISO 9141-2 (older European/Asian)"),
    KWP_5BAUD("4", "ISO 14230-4 KWP, 5-baud init"),
    KWP_FAST("5", "ISO 14230-4 KWP, fast init"),
    CAN_11_500("6", "ISO 15765-4 CAN 11-bit 500k (most 2008+ cars)"),
    CAN_29_500("7", "ISO 15765-4 CAN 29-bit 500k"),
    CAN_11_250("8", "ISO 15765-4 CAN 11-bit 250k"),
    CAN_29_250("9", "ISO 15765-4 CAN 29-bit 250k"),
}

/** ELM327 adaptive timing (`ATAT`). OFF uses a fixed adapter timeout (`ATST`). */
enum class AdaptiveTiming(val code: String, val label: String) {
    NORMAL("1", "Adaptive (recommended)"),
    AGGRESSIVE("2", "Adaptive, aggressive (faster, may miss slow modules)"),
    OFF("0", "Off: fixed adapter timeout"),
}

/**
 * Connection tuning. [RECOMMENDED] is what Everyday and DIY levels always use; only the
 * Advanced level applies a customized copy (see [effectiveFor]). Every value is clamped, so a
 * bad stored value can't stall the connection. None of these settings can make the adapter
 * send anything other than the read-only commands allowed by [ReadOnlyCommandPolicy].
 */
data class ObdConnectionConfig(
    val protocol: ObdProtocol = ObdProtocol.AUTO,
    /** With a manual protocol: if the car doesn't answer on it, let the adapter search automatically (`ATSP An`). */
    val protocolFallbackToAuto: Boolean = true,
    /** How long the app waits for the adapter's `>` prompt on a normal request. */
    val responseTimeoutMillis: Int = 3_000,
    /** Extra attempts after a request times out. */
    val retries: Int = 0,
    val adaptiveTiming: AdaptiveTiming = AdaptiveTiming.NORMAL,
    /** Adapter-side wait for the vehicle when adaptive timing is off (`ATST`, ~4 ms steps). */
    val adapterTimeoutMillis: Int = 200,
    /** Pause between live-data polling rounds. */
    val pollIntervalMillis: Int = 1_000,
    val autoReconnect: Boolean = true,
    val reconnectAttempts: Int = 3,
    /** Expert: ask the adapter to drop spaces from responses (`ATS0`) for slightly faster transfers. */
    val compactResponses: Boolean = false,
    /** Keep a redacted, in-memory log of adapter traffic. Off by default. */
    val communicationLog: Boolean = false,
) {
    fun clamped() = copy(
        responseTimeoutMillis = responseTimeoutMillis.coerceIn(TIMEOUT_RANGE),
        retries = retries.coerceIn(RETRY_RANGE),
        adapterTimeoutMillis = adapterTimeoutMillis.coerceIn(ADAPTER_TIMEOUT_RANGE),
        pollIntervalMillis = pollIntervalMillis.coerceIn(POLL_RANGE),
        reconnectAttempts = reconnectAttempts.coerceIn(RECONNECT_RANGE),
    )

    /** The `ATSP` command for the chosen protocol. */
    val protocolCommand: String
        get() = when {
            protocol == ObdProtocol.AUTO -> "ATSP0"
            protocolFallbackToAuto -> "ATSPA${protocol.code}"
            else -> "ATSP${protocol.code}"
        }

    /** `ATST` value: hex count of 4.096 ms units, 01–FF. */
    val adapterTimeoutCommand: String
        get() = "ATST%02X".format((adapterTimeoutMillis / 4.096).toInt().coerceIn(1, 0xFF))

    /** Settings that only take effect when the adapter is initialized again. */
    val initSignature: List<Any> get() = listOf(protocol, protocolFallbackToAuto, adaptiveTiming, adapterTimeoutMillis, compactResponses)

    /** Delays between reconnect attempts (empty when automatic reconnection is off). */
    val reconnectDelaysMillis: List<Long>
        get() = if (!autoReconnect) emptyList() else List(reconnectAttempts) { i -> listOf(2_000L, 5_000L, 10_000L).getOrElse(i) { 10_000L } }

    val isRecommended: Boolean get() = this == RECOMMENDED.copy(communicationLog = communicationLog)

    companion object {
        val RECOMMENDED = ObdConnectionConfig()
        val TIMEOUT_RANGE = 1_000..10_000
        val RETRY_RANGE = 0..3
        val ADAPTER_TIMEOUT_RANGE = 50..1_000
        val POLL_RANGE = 250..5_000
        val RECONNECT_RANGE = 0..5

        /**
         * What the connection actually uses. Everyday and DIY always get the recommended,
         * automatic configuration so a setting changed at Advanced can't silently degrade a
         * beginner's connection.
         */
        fun effectiveFor(level: ExplanationLevel, stored: ObdConnectionConfig): ObdConnectionConfig =
            if (level == ExplanationLevel.ADVANCED) stored.clamped() else RECOMMENDED
    }
}

/** What a connected adapter turned out to support. Null fields = not yet checked. */
data class AdapterCapabilities(
    val adaptiveTiming: Boolean? = null,
    val aggressiveTiming: Boolean? = null,
    val fixedTimeout: Boolean? = null,
    val compactResponses: Boolean? = null,
    val manualProtocol: Boolean? = null,
    val voltage: Boolean? = null,
    val description: Boolean? = null,
)

/** Adapter identity, reported by the adapter itself. */
data class AdapterInfo(
    /** Firmware string, e.g. "ELM327 v1.5". */
    val firmware: String?,
    /** `AT@1` device description, if the adapter has one. */
    val description: String? = null,
    val voltage: Double? = null,
    val protocolName: String? = null,
    val protocolNumber: String? = null,
    val capabilities: AdapterCapabilities = AdapterCapabilities(),
) {
    /** Firmware "v2.1" is not a real ELM Electronics release; almost always a low-cost clone. */
    val likelyClone: Boolean get() = firmware?.contains("v2.1", ignoreCase = true) == true
}

/** Persists [ObdConnectionConfig] with the same key-value storage as the app settings. */
class ObdConfigStore(private val store: KeyValueStore) {
    private val state = MutableStateFlow(read())
    val config: StateFlow<ObdConnectionConfig> = state.asStateFlow()

    fun update(transform: (ObdConnectionConfig) -> ObdConnectionConfig) {
        val next = transform(state.value).clamped()
        state.value = next
        write(next)
    }

    fun restoreRecommended() = update { ObdConnectionConfig.RECOMMENDED }

    private fun read(): ObdConnectionConfig {
        val d = ObdConnectionConfig.RECOMMENDED
        fun int(key: String, def: Int) = store.getFloat(key)?.toInt() ?: def
        return ObdConnectionConfig(
            protocol = ObdProtocol.entries.firstOrNull { it.name == store.getString(K_PROTOCOL) } ?: d.protocol,
            protocolFallbackToAuto = store.getBoolean(K_FALLBACK) ?: d.protocolFallbackToAuto,
            responseTimeoutMillis = int(K_TIMEOUT, d.responseTimeoutMillis),
            retries = int(K_RETRIES, d.retries),
            adaptiveTiming = AdaptiveTiming.entries.firstOrNull { it.name == store.getString(K_TIMING) } ?: d.adaptiveTiming,
            adapterTimeoutMillis = int(K_ADAPTER_TIMEOUT, d.adapterTimeoutMillis),
            pollIntervalMillis = int(K_POLL, d.pollIntervalMillis),
            autoReconnect = store.getBoolean(K_AUTO_RECONNECT) ?: d.autoReconnect,
            reconnectAttempts = int(K_RECONNECTS, d.reconnectAttempts),
            compactResponses = store.getBoolean(K_COMPACT) ?: d.compactResponses,
            communicationLog = store.getBoolean(K_LOG) ?: d.communicationLog,
        ).clamped()
    }

    private fun write(c: ObdConnectionConfig) = store.write(
        mapOf(
            K_PROTOCOL to c.protocol.name,
            K_FALLBACK to c.protocolFallbackToAuto,
            K_TIMEOUT to c.responseTimeoutMillis.toFloat(),
            K_RETRIES to c.retries.toFloat(),
            K_TIMING to c.adaptiveTiming.name,
            K_ADAPTER_TIMEOUT to c.adapterTimeoutMillis.toFloat(),
            K_POLL to c.pollIntervalMillis.toFloat(),
            K_AUTO_RECONNECT to c.autoReconnect,
            K_RECONNECTS to c.reconnectAttempts.toFloat(),
            K_COMPACT to c.compactResponses,
            K_LOG to c.communicationLog,
        ),
    )

    private companion object {
        const val K_PROTOCOL = "obd_protocol"
        const val K_FALLBACK = "obd_protocol_fallback"
        const val K_TIMEOUT = "obd_response_timeout"
        const val K_RETRIES = "obd_retries"
        const val K_TIMING = "obd_adaptive_timing"
        const val K_ADAPTER_TIMEOUT = "obd_adapter_timeout"
        const val K_POLL = "obd_poll_interval"
        const val K_AUTO_RECONNECT = "obd_auto_reconnect"
        const val K_RECONNECTS = "obd_reconnect_attempts"
        const val K_COMPACT = "obd_compact_responses"
        const val K_LOG = "obd_communication_log"
    }
}

/** How the app picks an adapter without asking (Everyday level). */
object AdapterAutoSelector {
    sealed interface Choice {
        data class Selected(val device: AdapterDevice, val reason: String) : Choice
        data class NeedsUser(val candidates: List<AdapterDevice>) : Choice
        data object NoneFound : Choice
    }

    fun choose(paired: List<AdapterDevice>, lastAddress: String?): Choice {
        val usable = paired.filter { it.radio != AdapterRadio.LE_ONLY }
        usable.firstOrNull { it.address == lastAddress }?.let { return Choice.Selected(it, "the adapter you used last time") }
        val obdLike = usable.filter { it.looksLikeObdAdapter }
        return when {
            obdLike.size == 1 -> Choice.Selected(obdLike.single(), "the only paired device that looks like an OBD-II adapter")
            obdLike.size > 1 -> Choice.NeedsUser(obdLike)
            usable.isNotEmpty() -> Choice.NeedsUser(usable)
            else -> Choice.NoneFound
        }
    }
}

/** Transport support in this build. Only implemented transports are offered to the user. */
object TransportSupport {
    val implemented = setOf(ObdTransport.BLUETOOTH_CLASSIC)
    fun isImplemented(t: ObdTransport) = t in implemented
}
