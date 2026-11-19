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

/**
 * Decides who answers. Gemini is the primary intelligence whenever it's available; the app's own
 * logic answers only where it must:
 * - [Route.DETERMINISTIC]: conversation controls and safety. Never depends on the AI or the network.
 * - [Route.DATA_THEN_AI]: the app first reads the real data (trouble codes, live values, connection
 *   status) through its own services, then Gemini explains that verified result.
 * - [Route.AI]: everything else, grounded in the verified vehicle facts.
 */
object AiRouting {
    enum class Route { DETERMINISTIC, DATA_THEN_AI, AI }

    /** A request about this car's current values ("what's my coolant temperature?", "is my battery ok?"). */
    private val LIVE_REQUEST = Regex("\\b(my|current|currently|right now|now|reading|readings|is it|how hot|how much)\\b")
    /** A general question about codes, not a request to read them ("what are trouble codes?"). */
    private val CODE_CONCEPT = Regex("^(what|how|why) (is|are|do|does) (a |an |the )?(trouble|error|fault|diagnostic|obd|dtc)\\b")

    fun route(intent: AssistantIntent, normalizedUtterance: String): Route = when (intent) {
        AssistantIntent.EndConversation, AssistantIntent.PauseConversation, AssistantIntent.Repeat,
        AssistantIntent.CanIDrive,
        // Connection status is a device status read; its text can include the adapter's Bluetooth
        // name, which may contain a person's name, so it's never sent to Gemini.
        AssistantIntent.ConnectionStatus -> Route.DETERMINISTIC
        is AssistantIntent.Symptom -> if (intent.category.isUrgent) Route.DETERMINISTIC else Route.AI
        AssistantIntent.ReadTroubleCodes ->
            if (CODE_CONCEPT.containsMatchIn(normalizedUtterance)) Route.AI else Route.DATA_THEN_AI
        is AssistantIntent.LiveValue ->
            if (LIVE_REQUEST.containsMatchIn(normalizedUtterance)) Route.DATA_THEN_AI else Route.AI
        AssistantIntent.SensorReadings, AssistantIntent.Help,
        // Recalls and crash ratings come from the offline database first; Gemini explains them.
        AssistantIntent.Recalls, AssistantIntent.SafetyRating -> Route.DATA_THEN_AI
        // Code explanations, follow-ups, greetings, vehicle questions, non-urgent symptoms, and
        // anything unrecognized: real conversation with Gemini, grounded in verified facts.
        else -> Route.AI
    }

    /** The source to record for a deterministic answer. */
    fun deterministicSource(intent: AssistantIntent): ResponseSource = when (intent) {
        AssistantIntent.EndConversation, AssistantIntent.PauseConversation, AssistantIntent.Repeat -> ResponseSource.CONVERSATION_CONTROL
        AssistantIntent.ConnectionStatus -> ResponseSource.DEVICE_COMMAND
        else -> ResponseSource.SAFETY
    }
}

/** Gemini timeouts: configurable through Remote Config, clamped so a bad value can't break Penelope. */
object AiTimeouts {
    const val DEFAULT_MILLIS = 12_000L
    const val MIN_MILLIS = 6_000L
    const val MAX_MILLIS = 30_000L
    const val REMOTE_CONFIG_KEY = "penelope_ai_timeout_ms"
    /** After this long without an answer, the screen says "Still thinking…" (never spoken). */
    const val SLOW_NOTICE_MILLIS = 4_000L

    fun choose(remote: Long?): Long = remote?.takeIf { it > 0 }?.coerceIn(MIN_MILLIS, MAX_MILLIS) ?: DEFAULT_MILLIS
}

/**
 * Penelope with Gemini as the primary intelligence (see [AiRouting]). Safety and conversation
 * controls come from [deterministic] (the rule-based provider), which also reads real vehicle data
 * for Gemini to explain. When Gemini can't answer, the built-in answer is used and Penelope says
 * so every time; a fallback is never passed off as an AI answer. Every answer's source is recorded
 * in [sources].
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
     * Upper bound for one AI answer, including the client's own retries ([AiTimeouts]; Remote
     * Config in production). After this Penelope gives her built-in answer and says why.
     */
    private val aiTimeoutMillis: () -> Long = { AiTimeouts.DEFAULT_MILLIS },
    /** Whether the phone currently has validated internet. When false, Gemini isn't contacted at all. */
    private val internetAvailable: () -> Boolean = { true },
    /** Structured diagnostics (categories and status codes only, never conversation text). */
    private val log: com.aicarcompanion.app.connectivity.ConnectionLog? = null,
    /** Waits used when the provider gives none. */
    private val busyCooldownSeconds: Long = 30,
    private val dailyLimitCooldownSeconds: Long = 3_600,
    /** App Check / authentication: short, so registering the debug token takes effect quickly. */
    private val authCooldownSeconds: Long = 60,
    private val configCooldownSeconds: Long = 300,
    /** A rate-limit wait up to this long is waited out once, so the user still gets the AI answer. */
    private val maxInlineRetrySeconds: Long = 3,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    /** Where each answer came from (shown in Settings → Connection status). */
    val sources: ResponseSourceTracker = ResponseSourceTracker(log),
    /** Monotonic clock for request durations. */
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Offline triage guides give Gemini the right clarifying questions for a described symptom. */
    private val offline: com.aicarcompanion.app.diagnostics.knowledge.OfflineKnowledge = com.aicarcompanion.app.diagnostics.knowledge.NoOfflineKnowledge,
) : ReasoningProvider {

    private val _status = MutableStateFlow(initialStatus())
    val status: StateFlow<AiStatus> = _status.asStateFlow()

    private val history = ArrayDeque<AiTurn>()
    private var lastReply: AssistantReply? = null
    /** The failure Penelope last announced in full; later fallbacks in the same outage get a short notice. */
    private var announcedOutage: AiFailure? = null
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
        announcedOutage = null
        // "Reset conversation" is also the user's way to retry the AI right away.
        blockedUntil = null
        blockedReason = null
        consecutiveUnexplained = 0
        deterministic.resetContext()
    }

    /** One turn at a time: history, outage state and data reads are never shared between two turns. */
    private val turn = kotlinx.coroutines.sync.Mutex()

    override suspend fun respond(request: AssistantRequest): AssistantReply = turn.withLock { respondInTurn(request) }

    private suspend fun respondInTurn(request: AssistantRequest): AssistantReply {
        val started = clockMillis()
        val intent = parser.parse(request.utterance)
        if (intent == AssistantIntent.Repeat) lastReply?.let {
            sources.record(ResponseRecord(ResponseSource.CONVERSATION_CONTROL))
            return it
        }
        val route = AiRouting.route(intent, parser.normalize(request.utterance))
        if (route == AiRouting.Route.DETERMINISTIC) {
            val reply = deterministic.respond(request)
            sources.record(ResponseRecord(AiRouting.deterministicSource(intent), clockMillis() - started))
            return remember(request.utterance, reply)
        }
        if (!aiEnabled() || !client.isConfigured) {
            _status.value = initialStatus()
            val source = if (!aiEnabled()) ResponseSource.AI_OFF else ResponseSource.NOT_CONFIGURED
            sources.record(ResponseRecord(source, clockMillis() - started))
            return remember(request.utterance, deterministic.respond(request))
        }

        val ctx = context.current()
        // Real data first: the app reads codes, live values or the connection through its own
        // services; Gemini only explains that verified result.
        val verified: AssistantReply? = if (route == AiRouting.Route.DATA_THEN_AI) deterministic.respond(request) else null

        val mentioned = CODE.findAll(request.utterance.uppercase()).map { it.value }.toSet() +
            listOfNotNull(parser.extractTroubleCode(parser.normalize(request.utterance)))
        val allowedCodes = mentioned + ctx.demoCodes.map { it.code } + ctx.latestCodeRead?.codes?.map { it.code }.orEmpty() +
            verified?.text?.let { t -> CODE.findAll(t).map { it.value } }.orEmpty()
        val system = PromptBuilder.systemInstruction(request.assistantName, request.level, ctx, knowledge, mentioned, now()) +
            (verified?.let { PromptBuilder.verifiedResult(it.text) } ?: "") +
            triageFor(intent, request.utterance).orEmpty()

        val result = nextResult(system, request.utterance)
        val duration = clockMillis() - started
        return when (result) {
            is AiResult.Reply -> {
                val cleaned = ReplyGuard.clean(result.text)
                _status.value = AiStatus.Ready(result.model)
                consecutiveUnexplained = 0
                val restored = announcedOutage != null
                announcedOutage = null
                if (restored) log?.record(com.aicarcompanion.app.connectivity.LogArea.AI, "Recovered", mapOf("model" to result.model))
                val violation = ReplyGuard.check(cleaned, allowedCodes, verifiedFacts = verified?.text.orEmpty())
                if (violation != null) {
                    // The model stated something the app can't verify: say so, and give the verified answer.
                    sources.record(ResponseRecord(ResponseSource.INVALID_RESPONSE, duration, result.model, violation.name))
                    val fallback = verified ?: deterministic.respond(request)
                    remember(request.utterance, fallback.withNotice(AiNotices.UNVERIFIED), historyText = fallback.text)
                } else {
                    val source = if (verified != null) ResponseSource.GEMINI_WITH_VEHICLE_DATA else ResponseSource.GEMINI
                    sources.record(ResponseRecord(source, duration, result.model))
                    // Saved here only when Gemini's answer is used; a fallback's own logic saves it otherwise (never twice).
                    if (intent is AssistantIntent.Symptom) {
                        ctx.vehicle?.let { v ->
                            recorder?.save(DiagnosticObservation(UUID.randomUUID().toString(), v.id, now(), ObservationKind.SYMPTOM, request.utterance.trim(), null, DataOrigin.USER_ENTERED))
                        }
                    }
                    // Gemini answered this turn, so any follow-up question the built-in logic was
                    // waiting on is stale; clear it so a later fallback isn't misread as its answer.
                    deterministic.resetContext()
                    // Data answers keep their data label ("Read from your vehicle just now").
                    val answer = AssistantReply(cleaned, verified?.basis ?: ReplyBasis.AI_EXPLANATION)
                    // Only claim recovery after a real successful reply. The notice isn't kept in history.
                    remember(request.utterance, if (restored) answer.withNotice(AiNotices.RESTORED) else answer, historyText = answer.text)
                }
            }
            is AiResult.Failure -> {
                _status.value = if (result.reason == AiFailure.NOT_CONFIGURED) AiStatus.NotConfigured else AiStatus.Unavailable(result.reason)
                sources.record(ResponseRecord(ResponseSource.forFailure(result.reason), duration, client.modelName, result.reason.name, skipped = result.detail == SKIPPED))
                val fallback = verified ?: deterministic.respond(request)
                // Never a silent canned answer: the full notice once per outage, a short one after that.
                val notice = if (announcedOutage == result.reason) AiNotices.stillUnavailable(result.reason) else AiNotices.outage(result.reason)
                announcedOutage = result.reason
                // The notice is for the user only: Gemini must never see itself "saying" it's unavailable.
                remember(request.utterance, fallback.withNotice(notice), historyText = fallback.text)
            }
        }
    }

    /** The offline triage guide for a described symptom; a stricter match for unrecognized questions. */
    private fun triageFor(intent: AssistantIntent, utterance: String): String? {
        val min = when (intent) {
            is AssistantIntent.Symptom -> 1
            is AssistantIntent.Unknown -> 2
            else -> return null
        }
        return com.aicarcompanion.app.diagnostics.knowledge.TriageMatcher.best(offline.triageGuides(), utterance, min)?.let(PromptBuilder::triageGuide)
    }

    /**
     * Decides whether to ask Gemini at all, asks at most twice (once more only for a very short
     * server-requested wait), and records why it didn't.
     */
    private suspend fun nextResult(system: String, message: String): AiResult {
        val area = com.aicarcompanion.app.connectivity.LogArea.AI
        if (!internetAvailable()) {
            log?.record(area, "Skipped: no internet", mapOf("category" to AiFailure.OFFLINE))
            return AiResult.Failure(AiFailure.OFFLINE, SKIPPED)
        }
        val until = blockedUntil
        if (until != null && now().isBefore(until)) {
            log?.record(area, "Skipped: waiting before retrying", mapOf("category" to blockedReason, "secondsLeft" to java.time.Duration.between(now(), until).seconds))
            return AiResult.Failure(blockedReason ?: AiFailure.BUSY, SKIPPED)
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
            AiFailure.APP_CHECK -> authCooldownSeconds
            AiFailure.SERVICE_DISABLED, AiFailure.MODEL_UNAVAILABLE, AiFailure.NOT_CONFIGURED -> configCooldownSeconds
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
     * A late answer after the timeout is discarded (the request is cancelled), so replies never overlap.
     * Cancellation of the caller (e.g. the user ending the conversation) still propagates.
     */
    private suspend fun generateSafely(system: String, message: String): AiResult {
        val limit = aiTimeoutMillis()
        return try {
            withTimeoutOrNull(limit) { client.generate(system, history.toList(), message) }
                ?: AiResult.Failure(AiFailure.TIMEOUT, "No AI answer within ${limit / 1000} s")
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

    private companion object {
        val CODE = Regex("\\b[PCBU][0-3][0-9A-F]{3}\\b")
        /** Marks a failure where no request was sent (offline, or waiting after an error). */
        const val SKIPPED = "skipped"
    }
}
