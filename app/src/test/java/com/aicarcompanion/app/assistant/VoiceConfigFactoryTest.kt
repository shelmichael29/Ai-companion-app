@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.aicarcompanion.app.assistant

import com.aicarcompanion.app.assistant.persona.VoiceChoice
import com.aicarcompanion.app.assistant.persona.VoiceGender
import com.aicarcompanion.app.assistant.voice.SpeakResult
import com.aicarcompanion.app.assistant.voice.SpeechOutput
import com.aicarcompanion.app.assistant.voice.SpeechOutputState
import com.aicarcompanion.app.assistant.voice.VoiceConfig
import com.aicarcompanion.app.assistant.voice.VoiceConfigFactory
import com.aicarcompanion.app.assistant.voice.VoiceRequest
import com.aicarcompanion.app.assistant.voice.VoiceSettingsBinder
import com.aicarcompanion.app.data.local.AppSettings
import com.aicarcompanion.app.data.local.InMemorySettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceConfigFactoryTest {
    @Test fun `match personality uses the profile gender and slot`() {
        val c = VoiceConfigFactory.from(AppSettings(assistantProfileId = "ace"))
        assertEquals(VoiceRequest.Gendered(VoiceGender.MALE, 3), c.voice)
        assertEquals(VoiceRequest.Gendered(VoiceGender.FEMALE, 0), VoiceConfigFactory.from(AppSettings()).voice)
    }

    @Test fun `profile choice is independent of the selected personality`() {
        val c = VoiceConfigFactory.from(AppSettings(assistantProfileId = "luna", voiceChoice = VoiceChoice.Profile("oliver")))
        assertEquals(VoiceRequest.Gendered(VoiceGender.MALE, 0), c.voice)
    }

    @Test fun `installed voice becomes a named request that falls back to the personality voice`() {
        val c = VoiceConfigFactory.from(AppSettings(assistantProfileId = "max", voiceChoice = VoiceChoice.Installed("v1")))
        assertEquals(VoiceRequest.Named("v1", VoiceRequest.Gendered(VoiceGender.MALE, 2)), c.voice)
    }

    @Test fun `override is used for tile previews and matches the live path for the selected tile`() {
        val s = AppSettings(assistantProfileId = "nova")
        assertEquals(VoiceConfigFactory.from(s), VoiceConfigFactory.from(s, VoiceChoice.MatchPersonality))
        assertEquals(VoiceRequest.Gendered(VoiceGender.MALE, 1), VoiceConfigFactory.from(s, VoiceChoice.Profile("atlas")).voice)
    }

    @Test fun `rate pitch and engine carry over and are clamped`() {
        val c = VoiceConfigFactory.from(AppSettings(speechRate = 1.2f, speechPitch = 0.9f, ttsEngine = "com.x"))
        assertEquals(1.2f, c.rate); assertEquals(0.9f, c.pitch); assertEquals("com.x", c.engine)
    }

    private class Recorder : SpeechOutput {
        override val state = MutableStateFlow<SpeechOutputState>(SpeechOutputState.Ready)
        val configs = mutableListOf<VoiceConfig>()
        override suspend fun speak(text: String) = SpeakResult.Completed
        override fun stop() = Unit
        override fun configure(config: VoiceConfig) { configs += config }
    }

    @Test fun `binder applies every settings change without a restart and skips identical configs`() = runTest {
        val store = InMemorySettingsStore()
        val out = Recorder()
        val job = VoiceSettingsBinder(this, store, out).start()
        advanceUntilIdle()
        assertEquals(1, out.configs.size)
        store.update { it.copy(speechRate = 1.3f) }; advanceUntilIdle()
        store.update { it.copy(assistantProfileId = "ace") }; advanceUntilIdle()
        store.update { it.copy(customName = "Zed") }; advanceUntilIdle() // name doesn't affect voice
        store.update { it.copy(voiceChoice = VoiceChoice.Profile("luna")) }; advanceUntilIdle()
        assertEquals(4, out.configs.size)
        assertEquals(1.3f, out.configs[1].rate)
        assertEquals(VoiceRequest.Gendered(VoiceGender.MALE, 3), out.configs[2].voice)
        assertEquals(VoiceRequest.Gendered(VoiceGender.FEMALE, 3), out.configs[3].voice)
        job.cancel()
    }
}
