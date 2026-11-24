package com.aicarcompanion.app.assistant.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
    private val log: com.aicarcompanion.app.connectivity.ConnectionLog? = null,
) : SpeechOutput {

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<SpeechOutputState>(SpeechOutputState.Initializing)
    override val state: StateFlow<SpeechOutputState> = _state.asStateFlow()

    private val _resolution = MutableStateFlow<VoiceResolution?>(null)
    override val resolution: StateFlow<VoiceResolution?> = _resolution.asStateFlow()

    /** Set when the voice or config changed while speaking; applied at the start of the next utterance. */
    @Volatile private var pendingApply = false

    private val speakMutex = Mutex()
    private val finishLock = Any()
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
            override fun onStart(utteranceId: String) {
                synchronized(finishLock) {
                    val active = current ?: return
                    if (VoiceTuning.isCurrentUtterance(active.first, utteranceId)) {
                        _state.value = SpeechOutputState.Speaking
                    }
                }
            }
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

    private fun rawVoices(engine: TextToSpeech): List<VoiceTuning.RawVoice> =
        runCatching { engine.voices }.getOrNull().orEmpty().map {
            VoiceTuning.RawVoice(it.name, it.locale, it.isNetworkConnectionRequired, it.quality, it.features.orEmpty())
        }

    private fun setDefaultLanguage(engine: TextToSpeech): Boolean {
        val ok = { r: Int -> r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED }
        return ok(engine.setLanguage(Locale.getDefault())) || ok(engine.setLanguage(Locale.US))
    }

    /**
     * Picks the voice for the current request from the voices the engine really has and applies it,
     * otherwise the language default. Returns false if there is no usable language.
     */
    private fun applyLanguageAndVoice(engine: TextToSpeech): Boolean {
        pendingApply = false
        val resolved = VoiceSelector.resolve(config.voice, rawVoices(engine), Locale.getDefault())
        _resolution.value = resolved
        if (resolved is VoiceResolution.Voice) {
            val chosen = runCatching { engine.voices?.firstOrNull { it.name == resolved.voiceName } }.getOrNull()
            if (chosen != null && engine.setVoice(chosen) == TextToSpeech.SUCCESS) return true
            _resolution.value = VoiceResolution.FallbackDefault("That voice couldn't be started")
        }
        return setDefaultLanguage(engine)
    }

    private fun finish(result: SpeakResult, callbackUtteranceId: String? = null) {
        val c = synchronized(finishLock) {
            val active = current ?: return
            // An interrupted TTS engine can call back after a newer utterance starts.
            if (callbackUtteranceId != null && !VoiceTuning.isCurrentUtterance(active.first, callbackUtteranceId)) return
            current = null // Atomic with the identity check: only one callback may release focus.
            active
        }
        focus.release()
        if (_state.value == SpeechOutputState.Speaking) _state.value = SpeechOutputState.Ready
        c.second.complete(result)
    }

    override suspend fun speak(text: String): SpeakResult = speakMutex.withLock { speakLocked(text) }

    private suspend fun speakLocked(text: String): SpeakResult {
        // Wait briefly for initialization (first use, or after an engine change).
        repeat(50) { if (_state.value is SpeechOutputState.Initializing) delay(100) }
        when (val s = _state.value) {
            is SpeechOutputState.Unavailable -> return SpeakResult.Failed(s.reason)
            SpeechOutputState.Initializing -> return SpeakResult.Failed("Text-to-speech isn't ready")
            else -> Unit
        }
        if (pendingApply) applyLanguageAndVoice(tts)
        val first = attempt(text)
        val voice = _resolution.value as? VoiceResolution.Voice
        if (first is SpeakResult.Failed && voice?.requiresNetwork == true) {
            // A network voice can fail offline: retry once with the language default (focus is released per attempt).
            log?.record(com.aicarcompanion.app.connectivity.LogArea.VOICE, "Network voice failed; using the default voice")
            if (setDefaultLanguage(tts)) _resolution.value = VoiceResolution.FallbackDefault("The online voice wasn't available")
            pendingApply = true // Try the chosen voice again next time.
            return attempt(text)
        }
        return first
    }

    private suspend fun attempt(text: String): SpeakResult {
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
                return done.await()
            }
        }
        return try {
            done.await()
        } finally {
            if (!done.isCompleted) stop()
        }
    }

    override suspend fun preview(text: String, config: VoiceConfig): SpeakResult = speakMutex.withLock {
        val saved = this.config
        try {
            // Same configuration path as conversation speech; the engine is never changed by a preview.
            this.config = config.copy(engine = saved.engine)
            if (_state.value !is SpeechOutputState.Initializing) {
                tts.setSpeechRate(VoiceTuning.rate(config.rate))
                tts.setPitch(VoiceTuning.pitch(config.pitch))
                applyLanguageAndVoice(tts)
            }
            speakLocked(text)
        } finally {
            this.config = saved
            if (_state.value !is SpeechOutputState.Initializing) {
                tts.setSpeechRate(VoiceTuning.rate(saved.rate))
                tts.setPitch(VoiceTuning.pitch(saved.pitch))
                applyLanguageAndVoice(tts)
            }
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
        // Never switch voices under an utterance that is playing: the next utterance picks it up.
        if (current != null) pendingApply = true else applyLanguageAndVoice(tts)
    }

    override suspend fun resolve(request: VoiceRequest): VoiceResolution? {
        repeat(50) { if (_state.value is SpeechOutputState.Initializing) delay(100) }
        if (_state.value is SpeechOutputState.Unavailable) return null
        return VoiceSelector.resolve(request, rawVoices(tts), Locale.getDefault())
    }

    override suspend fun voices(): List<VoiceOption> {
        repeat(50) { if (_state.value is SpeechOutputState.Initializing) delay(100) }
        return VoiceTuning.voiceOptions(rawVoices(tts), Locale.getDefault())
    }

    override fun engines(): List<EngineOption> =
        runCatching { tts.engines }.getOrNull().orEmpty().map { EngineOption(it.name, it.label) }

    private companion object {
        const val LAST = "#last"
    }
}
