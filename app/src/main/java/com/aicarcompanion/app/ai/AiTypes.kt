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
    QUOTA("The AI's usage limit was reached. Try again later."),
    SERVICE_DISABLED("Firebase AI Logic isn't enabled for this project."),
    MODEL_UNAVAILABLE("The selected AI model isn't available."),
    BLOCKED("The AI declined to answer that."),
    EMPTY("The AI returned an empty answer."),
    OTHER("The AI service had a problem."),
}

sealed interface AiResult {
    data class Reply(val text: String, val model: String) : AiResult
    data class Failure(val reason: AiFailure, val detail: String? = null) : AiResult
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
    fun classify(typeName: String, message: String?, causeIsNetwork: Boolean): AiFailure {
        val m = message.orEmpty().lowercase()
        return when {
            typeName == "QuotaExceededException" || "quota" in m || "429" in m || "resource_exhausted" in m -> AiFailure.QUOTA
            typeName == "RequestTimeoutException" -> AiFailure.TIMEOUT
            typeName == "ServiceDisabledException" || typeName == "APINotConfiguredException" ||
                "has not been used in project" in m || "is disabled" in m -> AiFailure.SERVICE_DISABLED
            "app check" in m || "appcheck" in m || "app_check" in m -> AiFailure.APP_CHECK
            typeName == "PromptBlockedException" || typeName == "ContentBlockedException" -> AiFailure.BLOCKED
            typeName == "InvalidAPIKeyException" -> AiFailure.NOT_CONFIGURED
            ("not found" in m || "404" in m || "is not supported" in m) && "model" in m -> AiFailure.MODEL_UNAVAILABLE
            "401" in m || "403" in m || "permission_denied" in m || "unauthenticated" in m -> AiFailure.APP_CHECK
            causeIsNetwork || "unable to resolve host" in m || "failed to connect" in m -> AiFailure.OFFLINE
            else -> AiFailure.OTHER
        }
    }
}
