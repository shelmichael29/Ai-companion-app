package com.aicarcompanion.app.assistant.conversation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.aicarcompanion.app.data.local.ExplanationLevel
import java.time.Instant

/** What a reply was based on, so the UI (and future logs/exports) can show its reliability. */
enum class ReplyBasis(val label: String) {
    VEHICLE_PROFILE("From your vehicle profile"),
    RECORDED_DATA("From saved history"),
    LIVE_VEHICLE_DATA("Read from your vehicle just now"),
    SAMPLE_DATA("Sample data, not from your vehicle"),
    GENERAL_KNOWLEDGE("General information"),
    NOT_AVAILABLE("Information not available"),
    CONVERSATION("Assistant"),
    AI_EXPLANATION("AI explanation (Gemini) · not a reading from your car"),
    OFFLINE_REFERENCE("From Penelope's offline vehicle database · not specific to your car"),
}

/** Something the voice session should do after speaking a reply. */
enum class ConversationAction { END, PAUSE }

data class AssistantReply(
    val text: String,
    val basis: ReplyBasis,
    /** Text sent to speech output; defaults to [text]. */
    val spokenText: String = text,
    val action: ConversationAction? = null,
)

/** One request to the reasoning provider. */
data class AssistantRequest(
    val utterance: String,
    val assistantName: String,
    /** How much detail to give by default; the user can ask for more or less per question. */
    val level: ExplanationLevel = ExplanationLevel.EVERYDAY,
)

enum class Speaker { USER, ASSISTANT }

data class ConversationTurn(
    val id: Long,
    val speaker: Speaker,
    val text: String,
    val at: Instant,
    val basis: ReplyBasis? = null,
)

/**
 * Turns a user request into a reply. The rule-based provider ships first; an AI provider
 * (on-device or cloud) can implement this later, given the same origin-labeled context.
 */
interface ReasoningProvider {
    suspend fun respond(request: AssistantRequest): AssistantReply

    /** Forget follow-up context (e.g. when the transcript is cleared). */
    fun resetContext() {}
}

/** Keeps the transcript and routes each utterance to the reasoning provider. */
class ConversationManager(
    private val reasoning: ReasoningProvider,
    private val assistantName: () -> String,
    private val level: () -> ExplanationLevel = { ExplanationLevel.EVERYDAY },
    private val now: () -> Instant = Instant::now,
    private val maxTurns: Int = 100,
) {
    private val _turns = MutableStateFlow<List<ConversationTurn>>(emptyList())
    val turns: StateFlow<List<ConversationTurn>> = _turns.asStateFlow()
    private var nextId = 0L

    suspend fun handle(utterance: String): AssistantReply {
        add(Speaker.USER, utterance.trim(), null)
        val reply = try {
            reasoning.respond(AssistantRequest(utterance, assistantName(), level()))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AssistantReply("Sorry, something went wrong while I was working on that.", ReplyBasis.CONVERSATION)
        }
        add(Speaker.ASSISTANT, reply.text, reply.basis)
        return reply
    }

    /** Adds an assistant message that wasn't a reply to a request (e.g. an error notice). */
    fun addNotice(text: String) = add(Speaker.ASSISTANT, text, ReplyBasis.CONVERSATION)

    fun clear() {
        _turns.update { emptyList() }
        reasoning.resetContext()
    }

    private fun add(speaker: Speaker, text: String, basis: ReplyBasis?) {
        _turns.update { (it + ConversationTurn(nextId++, speaker, text, now(), basis)).takeLast(maxTurns) }
    }
}
