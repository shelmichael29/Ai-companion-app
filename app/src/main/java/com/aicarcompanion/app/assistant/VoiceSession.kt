package com.aicarcompanion.app.assistant

import com.aicarcompanion.app.assistant.conversation.AssistantReply
import com.aicarcompanion.app.assistant.conversation.ConversationAction
import com.aicarcompanion.app.assistant.conversation.ConversationManager
import com.aicarcompanion.app.assistant.voice.AudioFocusController
import com.aicarcompanion.app.assistant.voice.NoAudioFocus
import com.aicarcompanion.app.assistant.voice.SpeakResult
import com.aicarcompanion.app.assistant.voice.SpeechInput
import com.aicarcompanion.app.assistant.voice.SpeechInputError
import com.aicarcompanion.app.assistant.voice.SpeechInputEvent
import com.aicarcompanion.app.assistant.voice.SpeechOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch

sealed interface VoiceState {
    /** No conversation. The microphone is off. */
    data object Idle : VoiceState
    data class Listening(val partialText: String = "") : VoiceState
    data class Processing(val heard: String) : VoiceState
    data class Speaking(val reply: AssistantReply) : VoiceState
    /** Conversation paused (by the user, silence, the app going to background, or a call). Mic is off. */
    data class Paused(val reason: String) : VoiceState
    /** Something failed. The microphone is off. */
    data class Error(val message: String, val needsPermission: Boolean = false) : VoiceState
}

/**
 * Hands-free conversation: listen → think → speak → listen again, until the user ends or
 * pauses it. Design rules:
 * - Half-duplex: the microphone is never open while Penelope speaks, and listening resumes
 *   only after speech has finished plus [resumeDelayMillis], so she can't hear herself.
 *   As a second guard, a transcript that matches what she just said is discarded.
 * - No runaway loops: after [maxSilentTurns] consecutive turns with no speech, or repeated
 *   recognizer errors, the conversation pauses instead of restarting forever.
 * - The microphone is off whenever the state isn't [VoiceState.Listening].
 * Barge-in today is "tap to interrupt"; see docs/ARCHITECTURE.md for the path to voice barge-in.
 */
class VoiceSession(
    private val scope: CoroutineScope,
    private val input: SpeechInput,
    private val output: SpeechOutput,
    val conversation: ConversationManager,
    private val focus: AudioFocusController = NoAudioFocus(),
    private val continuousEnabled: () -> Boolean = { true },
    private val resumeDelayMillis: Long = 450,
    private val maxSilentTurns: Int = 2,
    private val maxBusyRetries: Int = 2,
    /**
     * Validated internet availability, if known. When speech recognition fails for lack of
     * internet during a hands-free conversation, Penelope pauses and resumes listening by herself
     * once this turns true again, instead of ending the conversation.
     */
    private val internetOnline: StateFlow<Boolean>? = null,
) {
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _conversationActive = MutableStateFlow(false)
    /** True while a hands-free conversation is running (listening resumes automatically). */
    val conversationActive: StateFlow<Boolean> = _conversationActive.asStateFlow()

    private val _inputLevel = MutableStateFlow(0f)
    /** Live microphone level (0..1) while listening; 0 otherwise. Drives the voice orb only. */
    val inputLevel: StateFlow<Float> = _inputLevel.asStateFlow()

    private var job: Job? = null
    private var silentTurns = 0
    private var busyRetries = 0
    private var lastSpoken: String = ""
    private var holdsFocus = false
    /** Set while paused because the internet dropped; cleared by any user action or resume. */
    private var waitingForNetwork: Job? = null
    private var pendingStatusNotice: String? = null
    /** Automatic resumes since the last successful turn; bounded so a flaky network can't loop. */
    private var networkResumes = 0

    val isSpeechInputAvailable: Boolean get() = input.isAvailable

    init {
        focus.onFocusLost = {
            // A recognizer may briefly take focus itself while listening; only react otherwise.
            if (_state.value !is VoiceState.Listening && (_conversationActive.value || _state.value is VoiceState.Speaking)) {
                pause("Paused because another app needed the audio (for example, a phone call).")
            }
        }
    }

    /** The single primary action for the microphone button. */
    fun onMicPressed() {
        when (_state.value) {
            is VoiceState.Listening -> input.finishListening() // "I'm done talking"
            is VoiceState.Processing -> Unit
            is VoiceState.Speaking -> interrupt()
            VoiceState.Idle, is VoiceState.Paused, is VoiceState.Error -> start()
        }
    }

    /** Starts a conversation (hands-free if enabled in Settings, otherwise a single turn). */
    fun start() {
        cancelNetworkWait()
        networkResumes = 0
        silentTurns = 0
        busyRetries = 0
        _conversationActive.value = continuousEnabled()
        if (_conversationActive.value) acquireFocus()
        listen()
    }

    /** Stops Penelope talking and listens right away (tap-to-interrupt barge-in). */
    fun interrupt() {
        output.stop()
        job?.cancel()
        silentTurns = 0
        listen()
    }

    /** Stops speech; in a conversation, listening resumes as after a normal reply. */
    fun stopSpeaking() {
        if (_state.value is VoiceState.Speaking && _conversationActive.value) interrupt() else output.stop()
    }

    /** Ends the conversation and turns the microphone off. */
    fun end() {
        cancelNetworkWait()
        job?.cancel()
        _inputLevel.value = 0f
        output.stop()
        _conversationActive.value = false
        // A status notice still waiting for a gap isn't lost: it's shown on screen instead.
        pendingStatusNotice?.let { conversation.addNotice(it) }
        pendingStatusNotice = null
        releaseFocus()
        _state.value = VoiceState.Idle
    }

    /** Pauses the conversation; tapping the mic resumes it. Microphone and speech stop. */
    fun pause(reason: String = "Paused. Tap the microphone to continue.") {
        if (_state.value == VoiceState.Idle && !_conversationActive.value) return
        job?.cancel()
        _inputLevel.value = 0f
        output.stop()
        _conversationActive.value = false
        releaseFocus()
        _state.value = VoiceState.Paused(reason)
    }

    /** The app left the foreground: never keep listening or talking in the background. */
    fun onAppBackgrounded() {
        when (_state.value) {
            is VoiceState.Listening, is VoiceState.Processing, is VoiceState.Speaking ->
                pause("Paused while the app was in the background. Tap the microphone to continue.")
            else -> if (_conversationActive.value) pause("Paused while the app was in the background.")
        }
    }

    /**
     * A short status message (e.g. "Your adapter disconnected. I'm reconnecting."), delivered
     * without interrupting anyone:
     * - **In a hands-free conversation:** it's spoken between turns, after Penelope finishes her
     *   current reply and before she listens again. Only the latest one is kept.
     * - **Otherwise:** it's added to the conversation on screen without speaking.
     */
    fun queueStatusNotice(text: String) {
        if (_conversationActive.value) pendingStatusNotice = text else conversation.addNotice(text)
    }

    /** Typed input: same pipeline, without the microphone. Continues the conversation if one is active. */
    fun submitText(text: String) {
        cancelNetworkWait()
        if (text.isBlank()) return
        output.stop()
        job?.cancel()
        job = scope.launch { respondTo(text) }
    }

    fun onPermissionDenied(permanently: Boolean) {
        _conversationActive.value = false
        releaseFocus()
        val message = if (permanently) {
            "Microphone access is turned off for this app. Turn it on in Android Settings to talk to me, or type below."
        } else {
            SpeechInputError.PERMISSION.userMessage + " You can also type your question."
        }
        _state.value = VoiceState.Error(message, needsPermission = true)
        conversation.addNotice(message)
    }

    private fun listen() {
        job?.cancel()
        _state.value = VoiceState.Listening()
        job = scope.launch {
            // Collect only until a final result or error. Stopping collection closes the
            // recognizer, so the microphone is guaranteed off before Penelope thinks or speaks.
            val outcome = input.listen()
                .transformWhile { event ->
                    emit(event)
                    event !is SpeechInputEvent.Final && event !is SpeechInputEvent.Error
                }
                .onEach {
                    when (it) {
                        is SpeechInputEvent.Partial -> _state.value = VoiceState.Listening(it.text)
                        is SpeechInputEvent.Level -> _inputLevel.value = it.level
                        else -> Unit
                    }
                }
                .filter { it !is SpeechInputEvent.Level }
                .lastOrNull()
            _inputLevel.value = 0f
            when (outcome) {
                is SpeechInputEvent.Final -> onHeard(outcome.text)
                is SpeechInputEvent.Error -> onRecognitionError(outcome.error)
                else -> if (_state.value is VoiceState.Listening) afterTurn()
            }
        }
    }

    private suspend fun onHeard(text: String) {
        if (EchoGuard.isEcho(text, lastSpoken)) {
            // Picked up Penelope's own voice; ignore it and listen again.
            afterTurn()
            return
        }
        silentTurns = 0
        busyRetries = 0
        networkResumes = 0
        respondTo(text)
    }

    private suspend fun respondTo(text: String) {
        _state.value = VoiceState.Processing(text)
        val reply = conversation.handle(text)
        lastSpoken = reply.spokenText
        _state.value = VoiceState.Speaking(reply)
        when (val result = output.speak(reply.spokenText)) {
            SpeakResult.Completed -> when (reply.action) {
                ConversationAction.END -> end()
                ConversationAction.PAUSE -> pause()
                null -> afterTurn()
            }
            // Interruptions (tap, Stop, focus loss) already moved the state on.
            SpeakResult.Interrupted -> if (_state.value is VoiceState.Speaking) afterTurn()
            is SpeakResult.Failed -> {
                _conversationActive.value = false
                releaseFocus()
                _state.value = VoiceState.Error("I couldn't speak my answer (${result.reason}). It's shown on screen.")
            }
        }
    }

    /** Either listen again (conversation) or go idle (single turn). */
    private suspend fun afterTurn() {
        if (_conversationActive.value) {
            pendingStatusNotice?.let { notice ->
                pendingStatusNotice = null
                conversation.addNotice(notice)
                speakNotice(notice) // a safe moment: Penelope just finished, the mic is still off
            }
            delay(resumeDelayMillis) // Let speaker audio die away before opening the mic.
            if (_conversationActive.value) listen()
        } else {
            _state.value = VoiceState.Idle
        }
    }

    private suspend fun onRecognitionError(error: SpeechInputError) {
        val inConversation = _conversationActive.value
        when (error) {
            SpeechInputError.NO_SPEECH, SpeechInputError.NO_MATCH -> {
                silentTurns++
                if (!inConversation) {
                    _state.value = VoiceState.Error(error.userMessage)
                    speakNotice(error.userMessage)
                } else if (silentTurns >= maxSilentTurns) {
                    val msg = "I'll pause for now. Tap the microphone when you need me."
                    _state.value = VoiceState.Paused(msg)
                    speakNotice(msg)
                    _conversationActive.value = false
                    releaseFocus()
                } else {
                    if (error == SpeechInputError.NO_MATCH) speakNotice(error.userMessage)
                    afterTurn()
                }
            }
            SpeechInputError.NETWORK -> {
                val monitor = internetOnline
                if (inConversation && monitor != null && networkResumes < MAX_NETWORK_RESUMES) {
                    pauseUntilOnline(monitor)
                } else {
                    networkResumes = 0
                    stopWithError(error)
                }
            }
            SpeechInputError.BUSY -> {
                if (inConversation && busyRetries++ < maxBusyRetries) {
                    delay(800)
                    if (_conversationActive.value) listen()
                } else {
                    stopWithError(error)
                }
            }
            else -> stopWithError(error)
        }
    }

    /**
     * The internet dropped mid-conversation: say so once, release the microphone, and resume
     * listening automatically when validated internet returns. One wait per outage; ending the
     * conversation or tapping the mic cancels it.
     */
    private suspend fun pauseUntilOnline(online: StateFlow<Boolean>) {
        val msg = com.aicarcompanion.app.ai.AiNotices.OFFLINE
        _conversationActive.value = false
        releaseFocus()
        _state.value = VoiceState.Paused(msg)
        if (networkResumes == 0) speakNotice(msg) // said once per outage, not on every retry
        waitingForNetwork = scope.launch {
            if (online.value) delay(NETWORK_RECHECK_MILLIS) // the drop may already be over
            online.first { it }
            waitingForNetwork = null
            if (_state.value is VoiceState.Paused) {
                networkResumes++
                conversation.addNotice(com.aicarcompanion.app.ai.AiNotices.RESTORED)
                val resumes = networkResumes
                start()
                networkResumes = resumes // start() is also the user's tap; keep the automatic count
            }
        }
    }

    private fun cancelNetworkWait() {
        waitingForNetwork?.cancel()
        waitingForNetwork = null
    }

    private suspend fun stopWithError(error: SpeechInputError) {
        _conversationActive.value = false
        releaseFocus()
        _state.value = VoiceState.Error(error.userMessage, needsPermission = error == SpeechInputError.PERMISSION)
        if (error != SpeechInputError.PERMISSION) speakNotice(error.userMessage)
    }

    private suspend fun speakNotice(text: String) {
        lastSpoken = text
        output.speak(text)
    }

    private fun acquireFocus() {
        if (!holdsFocus) { focus.acquire(); holdsFocus = true }
    }

    private fun releaseFocus() {
        if (holdsFocus) { focus.release(); holdsFocus = false }
    }
}

private const val NETWORK_RECHECK_MILLIS = 3_000L
private const val MAX_NETWORK_RESUMES = 2

/** Detects when the recognizer transcribed Penelope's own speech (e.g. from car speakers). */
object EchoGuard {
    private fun words(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(" ").filter { it.isNotBlank() }

    fun isEcho(heard: String, lastSpoken: String): Boolean {
        val h = words(heard)
        if (h.size < 3 || lastSpoken.isBlank()) return false
        val spoken = words(lastSpoken).joinToString(" ")
        return spoken.contains(h.joinToString(" "))
    }
}
