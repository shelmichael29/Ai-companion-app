package com.aicarcompanion.app.assistant.voice

import com.aicarcompanion.app.data.local.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the speech output in step with the settings, so a new voice, speed or personality applies
 * to the next utterance without restarting the app. Previews use the same [VoiceConfigFactory].
 */
class VoiceSettingsBinder(
    private val scope: CoroutineScope,
    private val store: SettingsStore,
    private val output: SpeechOutput,
) {
    fun start(): Job = scope.launch {
        store.settings
            .map { VoiceConfigFactory.from(it) }
            .distinctUntilChanged()
            .collect { output.configure(it) }
    }
}
