package com.aicarcompanion.app.ai

/** One prior turn of the AI conversation. */
data class AiTurn(val role: Role, val text: String) {
    enum class Role { USER, MODEL }
}

/** Why an AI request didn't produce a reply. Each maps to a plain-language status. */
enum class AiFailure(val userMessage: String) {
    NOT_CONFIGURED("Smarter answers aren't set up in this build."),
    OFFLINE("No internet connection."),
    TIMEOUT("The AI took too long to answer."),
    APP_CHECK("This app copy isn't verified with Firebase App Check yet."),
    /** The daily quota is used up. Only for errors that say so; see [AiErrorClassifier]. */
    QUOTA("Today's AI usage limit was reached; it resets within a day."),
    /** Temporary: per-minute rate limit or an overloaded model. Usually clears within a minute. */
    BUSY("Too many requests at the moment; it should work again in a minute."),
    SERVICE_DISABLED("Firebase AI Logic isn't enabled for this project."),
    MODEL_UNAVAILABLE("The selected AI model isn't available."),
    BLOCKED("The AI declined to answer that."),
    EMPTY("The AI returned an empty answer."),
    OTHER("The AI service had a problem."),
}

sealed interface AiResult {
    data class Reply(val text: String, val model: String) : AiResult
    /**
     * @param retryAfterSeconds the provider's suggested wait (e.g. "Please retry in 40s"), if any.
     * @param httpStatus the provider's HTTP status code (e.g. 429, 503), if known. For logs only.
     */
    data class Failure(
        val reason: AiFailure,
        val detail: String? = null,
        val retryAfterSeconds: Long? = null,
        val httpStatus: Int? = null,
    ) : AiResult
}

/** What the user sees about AI availability. */
sealed interface AiStatus {
    data object Off : AiStatus
    data object NotConfigured : AiStatus
    /** Configured, not yet used in this session. */
    data class Standby(val model: String) : AiStatus
    data class Ready(val model: String) : AiStatus
    data class Unavailable(val reason: AiFailure) : AiStatus

    val label: String
        get() = when (this) {
            Off -> "Off: using built-in answers"
            NotConfigured -> "Not set up: using built-in answers"
            is Standby -> "On (Gemini, $model)"
            is Ready -> "Working (Gemini, $model)"
            is Unavailable -> "Unavailable: ${reason.userMessage} Using built-in answers."
        }
}

/**
 * A chat-capable language model. The Firebase AI Logic implementation is the production one;
 * tests use fakes. Implementations must never throw: failures come back as [AiResult.Failure].
 */
interface AiChatClient {
    val isConfigured: Boolean
    val modelName: String
    suspend fun generate(systemInstruction: String, history: List<AiTurn>, message: String): AiResult

    /**
     * Drops any cached client state so the next request starts fresh. Called after repeated
     * unexplained failures, never in the middle of a request. The conversation is kept by the caller.
     */
    fun reset() = Unit
}

/** Chooses the Gemini model: a stable Flash model only; never preview, "-latest" or retired names. */
object ModelSelection {
    const val DEFAULT_MODEL = "gemini-3.8-flash"
    const val FALLBACK_MODEL = "gemini-3.5-flash-lite"
    const val REMOTE_CONFIG_KEY = "penelope_model"

    private val STABLE_FLASH = Regex("^gemini-\\d+(\\.\\d+)?-flash(-lite)?$")
    private val RETIRED = setOf("gemini-2.0-flash", "gemini-2.0-flash-lite", "gemini-2.5-flash")

    fun isAcceptable(name: String?): Boolean =
        name != null && STABLE_FLASH.matches(name.trim()) && name.trim() !in RETIRED

    /** The Remote Config value if acceptable, otherwise the default. */
    fun choose(remoteValue: String?): String = remoteValue?.trim()?.takeIf(::isAcceptable) ?: DEFAULT_MODEL
}

/**
 * Maps SDK errors to [AiFailure] by exception type name, message and cause, so the mapping can
 * be unit-tested without the Firebase SDK.
 */
object AiErrorClassifier {
    private val RETRY_IN = Regex("(?i)retry(?:ing)? in\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(ms|s|sec|seconds?)?")
    private val RETRY_DELAY = Regex("(?i)\"?retry_?delay\"?\\s*[:=]\\s*\"?([0-9]+(?:\\.[0-9]+)?)s")
    private val HTTP_STATUS = Regex("\\b(4\\d\\d|5\\d\\d)\\b")

    /** The provider's suggested wait in whole seconds ("Please retry in 23.5s", "retryDelay": "23s"), if stated. */
    fun retryAfterSeconds(message: String?): Long? {
        val m = message ?: return null
        RETRY_DELAY.find(m)?.let { return kotlin.math.ceil(it.groupValues[1].toDouble()).toLong() }
        val r = RETRY_IN.find(m) ?: return null
        val value = r.groupValues[1].toDouble()
        val seconds = if (r.groupValues[2].equals("ms", ignoreCase = true)) value / 1000 else value
        return kotlin.math.ceil(seconds).toLong().coerceAtLeast(1)
    }

    /** The first HTTP status code mentioned in the error (e.g. 429, 503), for diagnostics. */
    fun httpStatus(message: String?): Int? = message?.let { HTTP_STATUS.find(it)?.value?.toInt() }

    fun classify(typeName: String, message: String?, causeIsNetwork: Boolean): AiFailure {
        val m = message.orEmpty().lowercase()
        val compact = m.replace("_", "").replace(" ", "").replace("-", "")
        val limited = typeName == "QuotaExceededException" || "quota" in m || "429" in m || "resource_exhausted" in m || "rate limit" in m
        // HTTP 429 covers both a short per-minute rate limit and the daily quota. Only call it the
        // daily quota when the error says so (e.g. quotaId "...PerDay..."); otherwise it's temporary.
        val daily = "perday" in compact || "daily" in m
        return when {
            limited && daily -> AiFailure.QUOTA
            limited -> AiFailure.BUSY
            typeName == "RequestTimeoutException" || "timeout" in typeName.lowercase() || "timed out" in m || "timeout" in m -> AiFailure.TIMEOUT
            typeName == "ServiceDisabledException" || typeName == "APINotConfiguredException" ||
                "has not been used in project" in m || "is disabled" in m -> AiFailure.SERVICE_DISABLED
            "appcheck" in typeName.lowercase() || "app check" in m || "appcheck" in m || "app_check" in m -> AiFailure.APP_CHECK
            "api key not valid" in m || "api_key_invalid" in m -> AiFailure.NOT_CONFIGURED
            typeName == "PromptBlockedException" || typeName == "ContentBlockedException" -> AiFailure.BLOCKED
            typeName == "InvalidAPIKeyException" -> AiFailure.NOT_CONFIGURED
            ("not found" in m || "404" in m || "is not supported" in m) && "model" in m -> AiFailure.MODEL_UNAVAILABLE
            "503" in m || "overloaded" in m || "service unavailable" in m -> AiFailure.BUSY
            "401" in m || "403" in m || "permission_denied" in m || "unauthenticated" in m -> AiFailure.APP_CHECK
            causeIsNetwork || "unable to resolve host" in m || "failed to connect" in m -> AiFailure.OFFLINE
            else -> AiFailure.OTHER
        }
    }
}
