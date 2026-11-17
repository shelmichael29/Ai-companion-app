package com.aicarcompanion.app.ai

import com.aicarcompanion.app.assistant.conversation.AssistantIntent
import com.aicarcompanion.app.assistant.conversation.AssistantReply
import com.aicarcompanion.app.assistant.conversation.AssistantRequest
import com.aicarcompanion.app.assistant.conversation.CommandParser
import com.aicarcompanion.app.assistant.conversation.ObservationRecorder
import com.aicarcompanion.app.assistant.conversation.ReasoningProvider
import com.aicarcompanion.app.assistant.conversation.ReplyBasis
import com.aicarcompanion.app.assistant.conversation.VehicleContextProvider
import com.aicarcompanion.app.diagnostics.dtc.DtcKnowledgeSource
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DiagnosticObservation
import com.aicarcompanion.app.domain.model.ObservationKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.UUID

/** Which requests must be answered by the app's own logic rather than the language model. */
object AiRouting {
    enum class Route { DETERMINISTIC, AI }

    private val DATA_REQUEST = Regex(
        "\\b(what'?s|what is|whats|what are|check|read|scan|show( me)?|tell me|how'?s|how is|current|currently|right now|reading|readings|level|any)\\b",
    )
    private val CODE_READ = Regex("\\b(read|check|scan|any|do i have|my (trouble |error |fault |diagnostic )?codes?|my check engine|check engine light (is |came )?on)\\b")

    fun route(intent: AssistantIntent, normalizedUtterance: String): Route = when (intent) {
        // Conversation control, safety and real vehicle data never depend on the AI.
        AssistantIntent.EndConversation, AssistantIntent.PauseConversation, AssistantIntent.Repeat,
        AssistantIntent.ConnectionStatus, AssistantIntent.CanIDrive, AssistantIntent.Help -> Route.DETERMINISTIC
        is AssistantIntent.Symptom -> if (intent.category.isUrgent) Route.DETERMINISTIC else Route.AI
        is AssistantIntent.ExplainCode -> Route.DETERMINISTIC
        is AssistantIntent.LiveValue, AssistantIntent.SensorReadings ->
            if (DATA_REQUEST.containsMatchIn(normalizedUtterance)) Route.DETERMINISTIC else Route.AI
        AssistantIntent.ReadTroubleCodes ->
            if (CODE_READ.containsMatchIn(normalizedUtterance)) Route.DETERMINISTIC else Route.AI
        // Everything conversational goes to Gemini, grounded in verified facts.
        else -> Route.AI
    }
}

/**
 * Penelope with Gemini. Safety-critical answers and real vehicle data come from
 * [deterministic] (the existing rule-based provider); open conversation goes to Gemini with a
 * multi-turn history and a grounding block of verified facts. Any AI problem falls back to the
 * deterministic answer, so Penelope always responds.
 */
class GeminiReasoningProvider(
    private val parser: CommandParser,
    private val deterministic: ReasoningProvider,
    private val context: VehicleContextProvider,
    private val client: AiChatClient,
    private val knowledge: DtcKnowledgeSource,
    private val aiEnabled: () -> Boolean,
    private val recorder: ObservationRecorder? = null,
    private val now: () -> Instant = Instant::now,
    private val maxHistoryTurns: Int = 12,
    /**
     * Upper bound for one AI answer, including the client's own retries. A hung or misbehaving
     * client can never leave Penelope silent: after this she gives her built-in answer.
     */
    private val aiTimeoutMillis: Long = 30_000,
    /** Whether the phone currently has validated internet. When false, Gemini isn't contacted at all. */
    private val internetAvailable: () -> Boolean = { true },
    /** Structured diagnostics (categories and status codes only, never conversation text). */
    private val log: com.aicarcompanion.app.connectivity.ConnectionLog? = null,
    /** Waits used when the provider gives none. */
    private val busyCooldownSeconds: Long = 30,
    private val dailyLimitCooldownSeconds: Long = 3_600,
    private val configCooldownSeconds: Long = 300,
    /** A rate-limit wait up to this long is waited out once, so the user still gets the AI answer. */
    private val maxInlineRetrySeconds: Long = 3,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : ReasoningProvider {

    private val _status = MutableStateFlow(initialStatus())
    val status: StateFlow<AiStatus> = _status.asStateFlow()

    private val history = ArrayDeque<AiTurn>()
    private var lastReply: AssistantReply? = null
    private var announcedOutage = false
    /** No Gemini requests before this time (server-requested wait, daily limit, configuration problem). */
    private var blockedUntil: Instant? = null
    private var blockedReason: AiFailure? = null
    private var consecutiveUnexplained = 0
    /** Only one Gemini request at a time; a newer question waits rather than piling on. */
    private val inFlight = kotlinx.coroutines.sync.Mutex()

    private fun initialStatus(): AiStatus = when {
        !aiEnabled() -> AiStatus.Off
        !client.isConfigured -> AiStatus.NotConfigured
        else -> AiStatus.Standby(client.modelName)
    }

    /** Re-evaluates availability (e.g. after the user flips the setting). */
    fun refreshStatus() {
        val current = _status.value
        _status.value = when {
            !aiEnabled() -> AiStatus.Off
            !client.isConfigured -> AiStatus.NotConfigured
            current is AiStatus.Off || current is AiStatus.NotConfigured -> AiStatus.Standby(client.modelName)
            else -> current
        }
    }

    override fun resetContext() {
        history.clear()
        lastReply = null
        announcedOutage = false
        // "Reset conversation" is also the user's way to retry the AI right away.
        blockedUntil = null
        blockedReason = null
        consecutiveUnexplained = 0
        deterministic.resetContext()
    }

    override suspend fun respond(request: AssistantRequest): AssistantReply {
        val intent = parser.parse(request.utterance)
        if (intent == AssistantIntent.Repeat) lastReply?.let { return it }

        if (!aiEnabled() || !client.isConfigured) {
            _status.value = initialStatus()
            return remember(request.utterance, deterministic.respond(request))
        }
        if (AiRouting.route(intent, parser.normalize(request.utterance)) == AiRouting.Route.DETERMINISTIC) {
            return remember(request.utterance, deterministic.respond(request))
        }

        val ctx = context.current()
        if (intent is AssistantIntent.Symptom) {
            ctx.vehicle?.let { v ->
                recorder?.save(DiagnosticObservation(UUID.randomUUID().toString(), v.id, now(), ObservationKind.SYMPTOM, request.utterance.trim(), null, DataOrigin.USER_ENTERED))
            }
        }
        val mentioned = Regex("\\b[PCBU][0-3][0-9A-F]{3}\\b").findAll(request.utterance.uppercase()).map { it.value }.toSet() +
            listOfNotNull(parser.extractTroubleCode(parser.normalize(request.utterance)))
        val allowedCodes = mentioned + ctx.demoCodes.map { it.code } + ctx.latestCodeRead?.codes?.map { it.code }.orEmpty()
        val system = PromptBuilder.systemInstruction(request.assistantName, request.level, ctx, knowledge, mentioned, now())

        val result = nextResult(system, request.utterance)
        return when (result) {
            is AiResult.Reply -> {
                val cleaned = ReplyGuard.clean(result.text)
                _status.value = AiStatus.Ready(result.model)
                consecutiveUnexplained = 0
                val restored = announcedOutage
                announcedOutage = false
                if (restored) log?.record(com.aicarcompanion.app.connectivity.LogArea.AI, "Recovered", mapOf("model" to result.model))
                val answer = if (ReplyGuard.check(cleaned, allowedCodes) != null) {
                    // The model stated something the app can't verify; answer with verified logic instead.
                    deterministic.respond(request)
                } else {
                    AssistantReply(cleaned, ReplyBasis.AI_EXPLANATION)
                }
                // Only claim recovery after a real successful reply. The notice isn't kept in history.
                remember(request.utterance, if (restored) answer.withNotice(AiNotices.RESTORED) else answer, historyText = answer.text)
            }
            is AiResult.Failure -> {
                _status.value = if (result.reason == AiFailure.NOT_CONFIGURED) AiStatus.NotConfigured else AiStatus.Unavailable(result.reason)
                val fallback = deterministic.respond(request)
                val reply = if (!announcedOutage) {
                    announcedOutage = true
                    fallback.withNotice(AiNotices.outage(result.reason))
                } else fallback
                // The notice is for the user only: Gemini must never see itself "saying" it's unavailable.
                remember(request.utterance, reply, historyText = fallback.text)
            }
        }
    }

    /**
     * Decides whether to ask Gemini at all, asks at most twice (once more only for a very short
     * server-requested wait), and records why it didn't.
     */
    private suspend fun nextResult(system: String, message: String): AiResult {
        val area = com.aicarcompanion.app.connectivity.LogArea.AI
        if (!internetAvailable()) {
            log?.record(area, "Skipped: no internet", mapOf("category" to AiFailure.OFFLINE))
            return AiResult.Failure(AiFailure.OFFLINE, "No validated internet connection")
        }
        val until = blockedUntil
        if (until != null && now().isBefore(until)) {
            log?.record(area, "Skipped: waiting before retrying", mapOf("category" to blockedReason, "secondsLeft" to java.time.Duration.between(now(), until).seconds))
            return AiResult.Failure(blockedReason ?: AiFailure.BUSY, "Waiting before retrying")
        }
        blockedUntil = null
        blockedReason = null
        var result = inFlight.withLock { generateSafely(system, message) }
        if (result is AiResult.Failure && result.reason == AiFailure.BUSY) {
            val wait = result.retryAfterSeconds
            if (wait != null && wait in 1..maxInlineRetrySeconds) {
                logFailure(result, retry = "once after ${wait}s")
                sleep(wait * 1_000)
                result = inFlight.withLock { generateSafely(system, message) }
            }
        }
        if (result is AiResult.Failure) onFailure(result)
        return result
    }

    private fun onFailure(f: AiResult.Failure) {
        val cooldown: Long? = when (f.reason) {
            AiFailure.BUSY -> f.retryAfterSeconds ?: busyCooldownSeconds
            AiFailure.QUOTA -> f.retryAfterSeconds ?: dailyLimitCooldownSeconds
            AiFailure.APP_CHECK, AiFailure.SERVICE_DISABLED, AiFailure.MODEL_UNAVAILABLE, AiFailure.NOT_CONFIGURED -> configCooldownSeconds
            // A dropped network or a slow answer: the next question may simply work.
            AiFailure.OFFLINE, AiFailure.TIMEOUT, AiFailure.BLOCKED, AiFailure.EMPTY, AiFailure.OTHER -> null
        }
        if (cooldown != null) {
            blockedUntil = now().plusSeconds(cooldown)
            blockedReason = f.reason
        }
        if (f.reason == AiFailure.OTHER || f.reason == AiFailure.EMPTY) {
            if (++consecutiveUnexplained >= 2) {
                client.reset()
                consecutiveUnexplained = 0
                log?.record(com.aicarcompanion.app.connectivity.LogArea.AI, "AI client reset after repeated unexplained failures")
            }
        }
        logFailure(f, retry = cooldown?.let { "not before ${it}s" } ?: "next question")
    }

    private fun logFailure(f: AiResult.Failure, retry: String) {
        log?.record(
            com.aicarcompanion.app.connectivity.LogArea.AI, "Request failed",
            mapOf("category" to f.reason, "httpStatus" to f.httpStatus, "retryAfterSeconds" to f.retryAfterSeconds, "nextAttempt" to retry),
        )
    }

    private fun AssistantReply.withNotice(notice: String) = copy(text = "$notice $text", spokenText = "$notice $spokenText")

    /**
     * Calls the AI client without letting it crash or stall Penelope: any exception, or no answer
     * within [aiTimeoutMillis], becomes an [AiResult.Failure], which falls back to the built-in answer.
     * Cancellation of the caller (e.g. the user ending the conversation) still propagates.
     */
    private suspend fun generateSafely(system: String, message: String): AiResult = try {
        withTimeoutOrNull(aiTimeoutMillis) { client.generate(system, history.toList(), message) }
            ?: AiResult.Failure(AiFailure.TIMEOUT, "No AI answer within ${aiTimeoutMillis / 1000} s")
    } catch (e: TimeoutCancellationException) {
        AiResult.Failure(AiFailure.TIMEOUT, "AI client timed out")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AiResult.Failure(AiErrorClassifier.classify(e.javaClass.simpleName, e.message, e.hasNetworkCause()), "Unexpected AI error: ${e.javaClass.simpleName}")
    } catch (e: LinkageError) {
        // e.g. a missing or mismatched AI SDK class; the rest of the app keeps working.
        AiResult.Failure(AiFailure.OTHER, "AI library unavailable: ${e.javaClass.simpleName}")
    }

    private fun Throwable.hasNetworkCause(): Boolean = generateSequence(this) { it.cause }.take(8).any { it is java.io.IOException }

    /** Keeps both AI and deterministic turns in the AI history so follow-ups have full context. */
    private fun remember(userText: String, reply: AssistantReply, historyText: String = reply.text): AssistantReply {
        if (reply.action == null) {
            history.addLast(AiTurn(AiTurn.Role.USER, userText.trim()))
            history.addLast(AiTurn(AiTurn.Role.MODEL, historyText))
            while (history.size > maxHistoryTurns * 2) history.removeFirst()
            lastReply = reply
        }
        return reply
    }
}
