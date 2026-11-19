package com.aicarcompanion.app.ai

/**
 * What Penelope says when her smarter answers are unavailable, and when they come back. Short,
 * plain and accurate: an internet problem is never described as a usage limit, and the daily
 * limit is only mentioned when the provider actually said so.
 *
 * These notices are shown to the user but are NEVER added to the conversation history sent to
 * Gemini (see GeminiFalseQuotaRegressionTest).
 */
object AiNotices {
    const val OFFLINE = "Your internet connection was interrupted. I'll reconnect when it's available."
    const val TEMPORARY = "I'm having trouble reaching the AI service. Your conversation is saved."
    const val DAILY_LIMIT = "Today's AI usage limit has been reached, so I'll use my built-in answers until it resets. Your conversation is saved."
    const val RESTORED = "Connection restored. We can continue."
    /** Gemini answered, but with something the app couldn't verify (an invented code or reading). */
    const val UNVERIFIED = "I couldn't double-check the AI's answer, so here's what I know for sure."

    /** The notice for a failure reason. Configuration problems name the specific cause. */
    fun outage(reason: AiFailure): String = when (reason) {
        AiFailure.OFFLINE -> OFFLINE
        AiFailure.QUOTA -> DAILY_LIMIT
        AiFailure.BUSY, AiFailure.TIMEOUT, AiFailure.OTHER, AiFailure.EMPTY -> TEMPORARY
        AiFailure.NOT_CONFIGURED, AiFailure.APP_CHECK, AiFailure.SERVICE_DISABLED, AiFailure.MODEL_UNAVAILABLE, AiFailure.BLOCKED ->
            "My smarter answers aren't available right now (${reason.userMessage.trimEnd('.').replaceFirstChar { it.lowercase() }}). Your conversation is saved."
    }

    /**
     * The short notice for later answers in the same outage, so a built-in answer is never passed
     * off as an AI answer, without repeating the full explanation every time.
     */
    fun stillUnavailable(reason: AiFailure): String = when (reason) {
        AiFailure.OFFLINE -> "Still offline, so here's my basic answer."
        else -> "My smarter answers are still unavailable, so here's my basic answer."
    }

    /** True if [text] begins with any outage, recovery or verification notice. */
    fun startsWithNotice(text: String): Boolean =
        (AiFailure.entries.flatMap { listOf(outage(it), stillUnavailable(it)) } + RESTORED + UNVERIFIED).any { text.startsWith(it) }
}
