package com.aicarcompanion.app.ai

import com.aicarcompanion.app.connectivity.ConnectionLog
import com.aicarcompanion.app.connectivity.LogArea
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where one of Penelope's answers came from. Shown in Settings → Connection status. */
enum class ResponseSource(val label: String, val isGemini: Boolean = false) {
    GEMINI("Gemini", isGemini = true),
    /** Gemini explained a result the app read first (codes, live values, connection). */
    GEMINI_WITH_VEHICLE_DATA("Gemini, explaining verified vehicle data", isGemini = true),
    SAFETY("Built-in safety answer"),
    CONVERSATION_CONTROL("Conversation control"),
    DEVICE_COMMAND("Device command or status (built-in)"),
    OFFLINE_FALLBACK("Built-in answer: no internet"),
    AI_OFF("Built-in answer: smarter answers turned off"),
    NOT_CONFIGURED("Built-in answer: Gemini isn't set up in this build"),
    AI_TIMEOUT("Built-in answer: Gemini timed out"),
    AUTH_ERROR("Built-in answer: App Check or authentication error"),
    RATE_LIMIT("Built-in answer: Gemini rate limit (temporary)"),
    QUOTA("Built-in answer: daily Gemini quota reached"),
    INVALID_RESPONSE("Built-in answer: Gemini's reply failed verification"),
    AI_SERVICE_ERROR("Built-in answer: other Gemini service error"),
    ;

    companion object {
        fun forFailure(reason: AiFailure): ResponseSource = when (reason) {
            AiFailure.OFFLINE -> OFFLINE_FALLBACK
            AiFailure.TIMEOUT -> AI_TIMEOUT
            // NOT_CONFIGURED from a request means the key or project was rejected: an authentication problem.
            AiFailure.APP_CHECK, AiFailure.NOT_CONFIGURED -> AUTH_ERROR
            AiFailure.BUSY -> RATE_LIMIT
            AiFailure.QUOTA -> QUOTA
            AiFailure.EMPTY -> INVALID_RESPONSE
            AiFailure.SERVICE_DISABLED, AiFailure.MODEL_UNAVAILABLE, AiFailure.BLOCKED, AiFailure.OTHER -> AI_SERVICE_ERROR
        }
    }
}

/** One answer's origin. Privacy: no question, answer, names, tokens or keys; only these fields. */
data class ResponseRecord(
    val source: ResponseSource,
    val durationMillis: Long? = null,
    val model: String? = null,
    /** The failure category or guard violation, if any. */
    val category: String? = null,
    /** True when no Gemini request was made because the app was waiting after an earlier error. */
    val skipped: Boolean = false,
)

/**
 * Remembers where the latest answer came from and writes one privacy-safe line per answer to
 * the diagnostic log (Settings → Connection status).
 */
class ResponseSourceTracker(private val log: ConnectionLog? = null) {
    private val _last = MutableStateFlow<ResponseRecord?>(null)
    val last: StateFlow<ResponseRecord?> = _last.asStateFlow()

    fun record(record: ResponseRecord) {
        _last.value = record
        log?.record(
            LogArea.AI, "Answer",
            mapOf(
                "source" to record.source.name,
                "ms" to record.durationMillis,
                "model" to record.model,
                "category" to record.category,
                "skipped" to record.skipped.takeIf { it },
            ),
        )
    }

    companion object {
        /** Plain summary for the status screen, e.g. "Gemini (gemini-3.8-flash), 1.8 s". */
        fun describe(r: ResponseRecord?): String {
            if (r == null) return "No answers yet in this session."
            val extras = listOfNotNull(r.model?.takeIf { r.source.isGemini }, r.durationMillis?.let { String.format(java.util.Locale.US, "%.1f s", it / 1000.0) })
            return r.source.label + if (extras.isEmpty()) "" else " (" + extras.joinToString(", ") + ")"
        }
    }
}
