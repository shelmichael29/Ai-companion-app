package com.aicarcompanion.app.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Android TextToSpeech with transient audio focus (music/navigation duck while Penelope talks)
 * and interruption: losing focus, [stop], or cancellation ends the current utterance.
 */
class AndroidSpeechOutput(context: Context) : SpeechOutput {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val _state = MutableStateFlow<SpeechOutputState>(SpeechOutputState.Initializing)
    override val state: StateFlow<SpeechOutputState> = _state.asStateFlow()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<SpeakResult>>()
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
        }
        .build()

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        if (status == TextToSpeech.SUCCESS) configure() else {
            _state.value = SpeechOutputState.Unavailable("No text-to-speech engine is available on this phone.")
        }
    }

    private fun configure() {
        val result = tts.setLanguage(Locale.getDefault())
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            val fallback = tts.setLanguage(Locale.US)
            if (fallback == TextToSpeech.LANG_MISSING_DATA || fallback == TextToSpeech.LANG_NOT_SUPPORTED) {
                _state.value = SpeechOutputState.Unavailable("Voice data isn't installed. Check your phone's text-to-speech settings.")
                return
            }
        }
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) { _state.value = SpeechOutputState.Speaking }
            override fun onDone(utteranceId: String) = finish(utteranceId, SpeakResult.Completed)
            override fun onStop(utteranceId: String, interrupted: Boolean) = finish(utteranceId, SpeakResult.Interrupted)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finish(utteranceId, SpeakResult.Failed("Speech engine error"))
            override fun onError(utteranceId: String, errorCode: Int) =
                finish(utteranceId, SpeakResult.Failed("Speech engine error $errorCode"))
        })
        _state.value = SpeechOutputState.Ready
    }

    private fun finish(id: String, result: SpeakResult) {
        pending.remove(id)?.complete(result)
        if (pending.isEmpty()) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            if (_state.value == SpeechOutputState.Speaking) _state.value = SpeechOutputState.Ready
        }
    }

    override suspend fun speak(text: String): SpeakResult {
        val current = _state.value
        if (current is SpeechOutputState.Unavailable) return SpeakResult.Failed(current.reason)
        if (current is SpeechOutputState.Initializing) {
            // Wait briefly for the engine to finish initializing.
            repeat(30) { if (_state.value is SpeechOutputState.Initializing) kotlinx.coroutines.delay(100) }
            if (_state.value !is SpeechOutputState.Ready) return SpeakResult.Failed("Text-to-speech isn't ready")
        }
        stop()
        audioManager.requestAudioFocus(focusRequest)
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<SpeakResult>()
        pending[id] = done
        val chunk = text.take(TextToSpeech.getMaxSpeechInputLength() - 1)
        val queued = tts.speak(chunk, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        if (queued != TextToSpeech.SUCCESS) finish(id, SpeakResult.Failed("Couldn't start speaking"))
        return try {
            done.await()
        } finally {
            if (!done.isCompleted) stop()
        }
    }

    override fun stop() {
        if (pending.isEmpty()) return
        tts.stop()
        pending.keys.toList().forEach { finish(it, SpeakResult.Interrupted) }
    }

    override fun setRate(rate: Float) {
        tts.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }
}
