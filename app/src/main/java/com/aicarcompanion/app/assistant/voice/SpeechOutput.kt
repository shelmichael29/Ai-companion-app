package com.aicarcompanion.app.assistant.voice

import kotlinx.coroutines.flow.StateFlow

sealed interface SpeechOutputState {
    data object Initializing : SpeechOutputState
    data object Ready : SpeechOutputState
    data object Speaking : SpeechOutputState
    data class Unavailable(val reason: String) : SpeechOutputState
}

sealed interface SpeakResult {
    data object Completed : SpeakResult
    /** Stopped by the user, another app taking audio focus, or a new request. */
    data object Interrupted : SpeakResult
    data class Failed(val reason: String) : SpeakResult
}

/**
 * Text-to-speech. The Android engine is the first implementation; a higher-quality voice
 * service (e.g. ElevenLabs streaming audio) can implement this interface later. Cloud voices
 * must not embed API keys in the app; route them through a user-supplied key or a backend.
 */
interface SpeechOutput {
    val state: StateFlow<SpeechOutputState>

    /** Speaks [text] and suspends until finished or interrupted. Cancelling stops speech. */
    suspend fun speak(text: String): SpeakResult

    /** Stops speaking immediately. */
    fun stop()

    fun setRate(rate: Float)
}
