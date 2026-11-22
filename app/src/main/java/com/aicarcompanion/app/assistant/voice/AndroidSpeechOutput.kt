package com.aicarcompanion.app.assistant.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.UUID

/**
 * Android TextToSpeech.
 *
 * Voice quality notes: pitch is always set explicitly (default 1.0) so a raised system-wide
 * pitch setting can't make Penelope sound squeaky; rate and pitch are clamped to natural
 * ranges; audio is played by the engine itself (no resampling in the app). Only one utterance
 * plays at a time: [speak] is serialized and flushes anything already queued.
 */
class AndroidSpeechOutput(
    context: Context,
    private val focus: AudioFocusController,
    private val attributes: android.media.AudioAttributes,
) : SpeechOutput {

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<SpeechOutputState>(SpeechOutputState.Initializing)
    override val state: StateFlow<SpeechOutputState> = _state.asStateFlow()

    private val speakMutex = Mutex()
    @Volatile private var current: Pair<String, CompletableDeferred<SpeakResult>>? = null
    @Volatile private var config = VoiceConfig()
    private var tts: TextToSpeech = create(null)
    private var engineInUse: String? = null

    private fun create(engine: String?): TextToSpeech {
        _state.value = SpeechOutputState.Initializing
        engineInUse = engine
        lateinit var instance: TextToSpeech
        instance = TextToSpeech(appContext, { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureEngine(instance)
            } else if (engine != null) {
                // Selected engine failed; fall back to the phone default.
                tts = create(null)
            } else {
                _state.value = SpeechOutputState.Unavailable("No text-to-speech engine is available on this phone.")
            }
        }, engine)
        return instance
    }

    private fun configureEngine(engine: TextToSpeech) {
        if (!applyLanguageAndVoice(engine)) {
            _state.value = SpeechOutputState.Unavailable("Voice data isn't installed. Check your phone's text-to-speech settings.")
            return
        }
        engine.setAudioAttributes(attributes)
        engine.setSpeechRate(VoiceTuning.rate(config.rate))
        engine.setPitch(VoiceTuning.pitch(config.pitch))
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) { _state.value = SpeechOutputState.Speaking }
            override fun onDone(utteranceId: String) {
                if (utteranceId.endsWith(LAST)) finish(SpeakResult.Completed, utteranceId)
            }
            override fun onStop(utteranceId: String, interrupted: Boolean) =
                finish(SpeakResult.Interrupted, utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) =
                finish(SpeakResult.Failed("Speech engine error"), utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) =
                finish(SpeakResult.Failed("Speech engine error $errorCode"), utteranceId)
        })
        _state.value = SpeechOutputState.Ready
    }

    /** Applies the chosen voice if it exists, otherwise the language default. Returns false if no usable language. */
    private fun applyLanguageAndVoice(engine: TextToSpeech): Boolean {
        val chosen = config.voice?.let { name -> runCatching { engine.voices?.firstOrNull { it.name == name } }.getOrNull() }
        if (chosen != null && engine.setVoice(chosen) == TextToSpeech.SUCCESS) return true
        val ok = { r: Int -> r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED }
        return ok(engine.setLanguage(Locale.getDefault())) || ok(engine.setLanguage(Locale.US))
    }

    private fun finish(result: SpeakResult, callbackUtteranceId: String? = null) {
        val c = current ?: return
        // An interrupted TTS engine can deliver callbacks after the next utterance starts.
        // Never complete or release audio focus for the wrong request.
        if (callbackUtteranceId != null && !VoiceTuning.isCurrentUtterance(c.first, callbackUtteranceId)) return
        current = null
        c.second.complete(result)
        focus.release()
        if (_state.value == SpeechOutputState.Speaking) _state.value = SpeechOutputState.Ready
    }

    override suspend fun speak(text: String): SpeakResult = speakMutex.withLock {
        // Wait briefly for initialization (first use, or after an engine change).
        repeat(50) { if (_state.value is SpeechOutputState.Initializing) delay(100) }
        when (val s = _state.value) {
            is SpeechOutputState.Unavailable -> return SpeakResult.Failed(s.reason)
            SpeechOutputState.Initializing -> return SpeakResult.Failed("Text-to-speech isn't ready")
            else -> Unit
        }
        stop()
        val done = CompletableDeferred<SpeakResult>()
        val id = UUID.randomUUID().toString()
        current = id to done
        focus.acquire()
        val chunks = VoiceTuning.chunk(text, TextToSpeech.getMaxSpeechInputLength() - 1)
        chunks.forEachIndexed { i, chunk ->
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val uid = if (i == chunks.lastIndex) "$id-$i$LAST" else "$id-$i"
            if (tts.speak(chunk, mode, Bundle(), uid) != TextToSpeech.SUCCESS) {
                finish(SpeakResult.Failed("Couldn't start speaking"))
                return@withLock done.await()
            }
        }
        try {
            done.await()
        } finally {
            if (!done.isCompleted) stop()
        }
    }

    override fun stop() {
        if (current == null) return
        tts.stop()
        finish(SpeakResult.Interrupted)
    }

    override fun configure(config: VoiceConfig) {
        val engineChanged = config.engine != engineInUse
        this.config = config
        if (engineChanged) {
            stop()
            runCatching { tts.shutdown() }
            tts = create(config.engine)
            return
        }
        if (_state.value is SpeechOutputState.Initializing) return // Applied when ready.
        tts.setSpeechRate(VoiceTuning.rate(config.rate))
        tts.setPitch(VoiceTuning.pitch(config.pitch))
        applyLanguageAndVoice(tts)
    }

    override suspend fun voices(): List<VoiceOption> {
        repeat(50) { if (_state.value is SpeechOutputState.Initializing) delay(100) }
        val all: Set<Voice> = runCatching { tts.voices }.getOrNull().orEmpty()
        return VoiceTuning.voiceOptions(
            all.map { VoiceTuning.RawVoice(it.name, it.locale, it.isNetworkConnectionRequired, it.quality, it.features.orEmpty()) },
            Locale.getDefault(),
        )
    }

    override fun engines(): List<EngineOption> =
        runCatching { tts.engines }.getOrNull().orEmpty().map { EngineOption(it.name, it.label) }

    private companion object {
        const val LAST = "#last"
    }
}
