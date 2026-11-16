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
) : ReasoningProvider {

    private val _status = MutableStateFlow(initialStatus())
    val status: StateFlow<AiStatus> = _status.asStateFlow()

    private val history = ArrayDeque<AiTurn>()
    private var lastReply: AssistantReply? = null
    private var announcedOutage = false

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

        return when (val result = generateSafely(system, request.utterance)) {
            is AiResult.Reply -> {
                val cleaned = ReplyGuard.clean(result.text)
                _status.value = AiStatus.Ready(result.model)
                announcedOutage = false
                if (ReplyGuard.check(cleaned, allowedCodes) != null) {
                    // The model stated something the app can't verify; answer with verified logic instead.
                    remember(request.utterance, deterministic.respond(request))
                } else {
                    remember(request.utterance, AssistantReply(cleaned, ReplyBasis.AI_EXPLANATION))
                }
            }
            is AiResult.Failure -> {
                _status.value = if (result.reason == AiFailure.NOT_CONFIGURED) AiStatus.NotConfigured else AiStatus.Unavailable(result.reason)
                val fallback = deterministic.respond(request)
                val reply = if (!announcedOutage) {
                    announcedOutage = true
                    val note = "I can't reach my smarter answers right now (${result.reason.userMessage.trimEnd('.').lowercase()}), so here's my basic answer. "
                    fallback.copy(text = note + fallback.text, spokenText = note + fallback.spokenText)
                } else fallback
                remember(request.utterance, reply)
            }
        }
    }

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
    private fun remember(userText: String, reply: AssistantReply): AssistantReply {
        if (reply.action == null) {
            history.addLast(AiTurn(AiTurn.Role.USER, userText.trim()))
            history.addLast(AiTurn(AiTurn.Role.MODEL, reply.text))
            while (history.size > maxHistoryTurns * 2) history.removeFirst()
            lastReply = reply
        }
        return reply
    }
}
