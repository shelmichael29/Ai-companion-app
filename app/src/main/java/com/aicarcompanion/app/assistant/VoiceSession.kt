package com.aicarcompanion.app.assistant

import com.aicarcompanion.app.assistant.conversation.AssistantReply
import com.aicarcompanion.app.assistant.conversation.ConversationManager
import com.aicarcompanion.app.assistant.voice.SpeakResult
import com.aicarcompanion.app.assistant.voice.SpeechInput
import com.aicarcompanion.app.assistant.voice.SpeechInputError
import com.aicarcompanion.app.assistant.voice.SpeechInputEvent
import com.aicarcompanion.app.assistant.voice.SpeechOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface VoiceState {
    data object Idle : VoiceState
    data class Listening(val partialText: String = "") : VoiceState
    data class Processing(val heard: String) : VoiceState
    data class Speaking(val reply: AssistantReply) : VoiceState
    data class Error(val message: String, val needsPermission: Boolean = false) : VoiceState
}

/**
 * Push-to-talk conversation loop: listen → process → speak. Tapping the microphone while the
 * assistant is speaking interrupts it and starts listening (barge-in by tap). App-scoped so
 * a conversation continues while the user moves between screens.
 */
class VoiceSession(
    private val scope: CoroutineScope,
    private val input: SpeechInput,
    private val output: SpeechOutput,
    val conversation: ConversationManager,
) {
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var job: Job? = null

    val isSpeechInputAvailable: Boolean get() = input.isAvailable

    /** The single primary action for the microphone button. */
    fun onMicPressed() {
        when (_state.value) {
            is VoiceState.Listening -> input.finishListening()
            is VoiceState.Processing -> Unit // Ignore taps while working; the reply comes shortly.
            is VoiceState.Speaking, VoiceState.Idle, is VoiceState.Error -> startListening()
        }
    }

    fun startListening() {
        output.stop()
        job?.cancel()
        _state.value = VoiceState.Listening()
        job = scope.launch {
            var finished = false
            input.listen().collect { event ->
                when (event) {
                    SpeechInputEvent.Ready -> Unit
                    is SpeechInputEvent.Partial -> _state.value = VoiceState.Listening(event.text)
                    is SpeechInputEvent.Final -> { finished = true; process(event.text) }
                    is SpeechInputEvent.Error -> { finished = true; fail(event.error) }
                }
            }
            if (!finished && _state.value is VoiceState.Listening) _state.value = VoiceState.Idle
        }
    }

    /** Typed input: same pipeline, without the microphone. */
    fun submitText(text: String) {
        if (text.isBlank()) return
        output.stop()
        job?.cancel()
        job = scope.launch { process(text) }
    }

    fun stopSpeaking() {
        output.stop()
    }

    /** Cancels whatever is happening and returns to idle. */
    fun cancel() {
        job?.cancel()
        output.stop()
        _state.value = VoiceState.Idle
    }

    fun onPermissionDenied(permanently: Boolean) {
        val message = if (permanently) {
            "Microphone access is turned off for this app. Turn it on in Android Settings to talk to me, or type below."
        } else {
            SpeechInputError.PERMISSION.userMessage + " You can also type your question."
        }
        _state.value = VoiceState.Error(message, needsPermission = true)
        conversation.addNotice(message)
    }

    private suspend fun process(text: String) {
        _state.value = VoiceState.Processing(text)
        val reply = conversation.handle(text)
        speak(reply)
    }

    private suspend fun speak(reply: AssistantReply) {
        _state.value = VoiceState.Speaking(reply)
        when (val result = output.speak(reply.spokenText)) {
            SpeakResult.Completed, SpeakResult.Interrupted -> if (_state.value is VoiceState.Speaking) _state.value = VoiceState.Idle
            is SpeakResult.Failed -> _state.value = VoiceState.Error(
                "I couldn't speak my answer (${result.reason}). It's shown on screen.",
            )
        }
    }

    private suspend fun fail(error: SpeechInputError) {
        _state.value = VoiceState.Error(error.userMessage, needsPermission = error == SpeechInputError.PERMISSION)
        // Speak short recognition problems aloud so the driver doesn't need to look at the screen.
        if (error == SpeechInputError.NO_MATCH || error == SpeechInputError.NO_SPEECH) {
            output.speak(error.userMessage)
        }
    }
}
