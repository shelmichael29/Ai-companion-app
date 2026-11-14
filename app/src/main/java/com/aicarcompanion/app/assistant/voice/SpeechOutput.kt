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

/** Voice configuration. Rate and pitch are independent; 1.0 / 1.0 is the engine's natural voice. */
data class VoiceConfig(
    val rate: Float = 1.0f,
    val pitch: Float = 1.0f,
    /** Engine package, or null for the phone's default engine. */
    val engine: String? = null,
    /** Voice id within the engine, or null for the engine's default voice for the language. */
    val voice: String? = null,
)

data class VoiceOption(
    val id: String,
    val label: String,
    val requiresNetwork: Boolean,
    val highQuality: Boolean,
)

data class EngineOption(val id: String, val label: String)

/**
 * Text-to-speech. The Android engine is the first implementation; a higher-quality voice
 * service (e.g. ElevenLabs streaming audio) can implement this interface later. Cloud voices
 * must not embed API keys in the app; route them through a user-supplied key or a backend.
 *
 * Implementations must never play two utterances at once: [speak] replaces anything playing.
 */
interface SpeechOutput {
    val state: StateFlow<SpeechOutputState>

    /** Speaks [text] and suspends until finished or interrupted. Cancelling stops speech. */
    suspend fun speak(text: String): SpeakResult

    /** Stops speaking immediately. */
    fun stop()

    /** Applies rate, pitch, engine and voice. Changing the engine re-initializes it. */
    fun configure(config: VoiceConfig)

    /** Voices for the current language from the current engine (empty if unknown). */
    suspend fun voices(): List<VoiceOption> = emptyList()

    /** Installed TTS engines. */
    fun engines(): List<EngineOption> = emptyList()
}

/**
 * Shared audio focus for the assistant. Reference-counted so the conversation session and
 * speech playback use a single focus request (two requests from one app would make Android
 * report a focus loss to ourselves and cut Penelope off).
 */
interface AudioFocusController {
    /** Called when another app (e.g. a phone call) takes focus. */
    var onFocusLost: (() -> Unit)?
    fun acquire()
    fun release()
}

/** No-op focus for tests and environments without audio. */
class NoAudioFocus : AudioFocusController {
    override var onFocusLost: (() -> Unit)? = null
    var held = 0
        private set
    override fun acquire() { held++ }
    override fun release() { if (held > 0) held-- }
}
