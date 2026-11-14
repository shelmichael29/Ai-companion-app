package com.aicarcompanion.app.assistant.voice

import kotlinx.coroutines.flow.Flow

/** Events from one listening session, in order: Ready → (Partial)* → Final | Error. */
sealed interface SpeechInputEvent {
    data object Ready : SpeechInputEvent
    data class Partial(val text: String) : SpeechInputEvent
    /** Microphone loudness, normalized to 0..1. Used only for visual feedback. */
    data class Level(val level: Float) : SpeechInputEvent
    data class Final(val text: String) : SpeechInputEvent
    data class Error(val error: SpeechInputError) : SpeechInputEvent
}

enum class SpeechInputError(val userMessage: String) {
    NO_MATCH("Sorry, I didn't catch that."),
    NO_SPEECH("I didn't hear anything."),
    NETWORK("Speech recognition needs an internet connection on this phone right now. You can also type your question."),
    AUDIO("I couldn't use the microphone. Another app may be using it."),
    PERMISSION("I need microphone permission to hear you."),
    BUSY("Speech recognition is busy. Try again in a moment."),
    LANGUAGE_UNAVAILABLE("Speech recognition for your language isn't available on this phone."),
    UNAVAILABLE("Speech recognition isn't available on this phone. You can type your question instead."),
    OTHER("Something went wrong with speech recognition. Please try again."),
}

/**
 * Speech-to-text. Push-to-talk only for now: [listen] runs one recognition session.
 * Future hands-free/wake-word activation should be a separate component that decides *when*
 * to call [listen]; Android doesn't permit continuous background listening by ordinary apps.
 */
interface SpeechInput {
    /** Whether a recognizer exists on this device. */
    val isAvailable: Boolean

    /** Starts listening when collected. Cancelling collection cancels recognition. */
    fun listen(): Flow<SpeechInputEvent>

    /** Stops capturing audio and delivers a [SpeechInputEvent.Final] for what was heard so far. */
    fun finishListening()
}

/** Converts the recognizer's RMS dB (roughly -2..10) to 0..1. */
fun normalizeRms(rmsDb: Float): Float = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)

/** Maps android.speech.SpeechRecognizer error codes (kept as ints so this is JVM-testable). */
object SpeechErrorMapper {
    fun map(code: Int): SpeechInputError = when (code) {
        1, 2, 4, 11 -> SpeechInputError.NETWORK // network timeout, network, server, server disconnected
        3 -> SpeechInputError.AUDIO
        5 -> SpeechInputError.OTHER // client
        6 -> SpeechInputError.NO_SPEECH
        7 -> SpeechInputError.NO_MATCH
        8, 10 -> SpeechInputError.BUSY // recognizer busy, too many requests
        9 -> SpeechInputError.PERMISSION
        12, 13 -> SpeechInputError.LANGUAGE_UNAVAILABLE
        else -> SpeechInputError.OTHER
    }

    /** Errors where an on-device recognizer might still succeed. */
    fun isNetworkRelated(code: Int) = code in setOf(1, 2, 4, 11)
}
