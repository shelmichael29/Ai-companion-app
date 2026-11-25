@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.aicarcompanion.app.ui

import com.aicarcompanion.app.ai.AiStatus
import com.aicarcompanion.app.assistant.VoiceSession
import com.aicarcompanion.app.assistant.conversation.AssistantReply
import com.aicarcompanion.app.assistant.conversation.AssistantRequest
import com.aicarcompanion.app.assistant.conversation.ConversationManager
import com.aicarcompanion.app.assistant.conversation.ReasoningProvider
import com.aicarcompanion.app.assistant.conversation.ReplyBasis
import com.aicarcompanion.app.assistant.persona.VoiceChoice
import com.aicarcompanion.app.assistant.persona.VoiceGender
import com.aicarcompanion.app.assistant.voice.SpeakResult
import com.aicarcompanion.app.assistant.voice.SpeechInput
import com.aicarcompanion.app.assistant.voice.SpeechInputEvent
import com.aicarcompanion.app.assistant.voice.SpeechOutput
import com.aicarcompanion.app.assistant.voice.SpeechOutputState
import com.aicarcompanion.app.assistant.voice.VoiceConfig
import com.aicarcompanion.app.assistant.voice.VoiceConfigFactory
import com.aicarcompanion.app.assistant.voice.VoiceRequest
import com.aicarcompanion.app.assistant.voice.VoiceResolution
import com.aicarcompanion.app.data.local.AppSettings
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.InMemorySettingsStore
import com.aicarcompanion.app.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsViewModelPersonalizationTest {
    private object SilentInput : SpeechInput {
        override val isAvailable = false
        override fun listen(): Flow<SpeechInputEvent> = emptyFlow()
        override fun finishListening() = Unit
    }

    private class FakeOutput : SpeechOutput {
        override val state = MutableStateFlow<SpeechOutputState>(SpeechOutputState.Ready)
        val previews = mutableListOf<Pair<String, VoiceConfig>>()
        var stops = 0
        override suspend fun speak(text: String) = SpeakResult.Completed
        override fun stop() { stops++ }
        override fun configure(config: VoiceConfig) = Unit
        override suspend fun resolve(request: VoiceRequest): VoiceResolution? = when {
            request is VoiceRequest.Gendered && request.gender == VoiceGender.MALE -> VoiceResolution.FallbackDefault("No male voice is installed on this phone", VoiceGender.MALE)
            else -> VoiceResolution.Voice("v", "Female voice 1 · works offline", VoiceGender.FEMALE, false, false)
        }
        override suspend fun preview(text: String, config: VoiceConfig): SpeakResult { previews += text to config; return SpeakResult.Completed }
    }

    private val reasoning = object : ReasoningProvider {
        override suspend fun respond(request: AssistantRequest) = AssistantReply("ok", ReplyBasis.CONVERSATION)
    }

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(store: InMemorySettingsStore, out: FakeOutput) = SettingsViewModel(
        store, out, VoiceSession(kotlinx.coroutines.MainScope(), SilentInput, out, ConversationManager(reasoning, { "Penelope" })),
        MutableStateFlow<AiStatus>(AiStatus.Off), "m", "none",
    )

    @Test fun `selecting a profile updates settings and leaves the knowledge level alone`() {
        val store = InMemorySettingsStore(AppSettings(explanationLevel = ExplanationLevel.ADVANCED))
        val vm = viewModel(store, FakeOutput())
        vm.selectProfile("oliver")
        assertEquals("oliver", store.settings.value.assistantProfileId)
        assertEquals(ExplanationLevel.ADVANCED, store.settings.value.explanationLevel)
        vm.selectProfile("bogus")
        assertEquals("penelope", store.settings.value.assistantProfileId)
    }

    @Test fun `rename sanitizes blank clears and unusable names are rejected without changing anything`() {
        val store = InMemorySettingsStore()
        val vm = viewModel(store, FakeOutput())
        vm.rename("  Jar\nvis  ")
        assertEquals("Jar vis", store.settings.value.customName)
        assertEquals("Jar vis", store.settings.value.assistantName)
        vm.rename("stop")
        assertNotNull(vm.nameError.value)
        assertEquals("Jar vis", store.settings.value.customName)
        vm.rename("")
        assertNull(vm.nameError.value)
        assertNull(store.settings.value.customName)
        assertEquals("Penelope", store.settings.value.assistantName)
    }

    @Test fun `a custom name is independent of the profile`() {
        val store = InMemorySettingsStore()
        val vm = viewModel(store, FakeOutput())
        vm.rename("Zoë"); vm.selectProfile("atlas")
        assertEquals("Zoë", store.settings.value.assistantName)
        assertEquals("atlas", store.settings.value.assistantProfileId)
    }

    @Test fun `selecting a profile always sets the voice to follow the profile`() {
        val store = InMemorySettingsStore(AppSettings(voiceChoice = VoiceChoice.Installed("v1")))
        val vm = viewModel(store, FakeOutput())
        vm.selectProfile("luna")
        assertEquals(VoiceChoice.MatchPersonality, store.settings.value.voiceChoice)
        assertEquals("luna", store.settings.value.assistantProfileId)
    }

    @Test fun `voice source is saved and applied to the next voice configuration`() {
        val store = InMemorySettingsStore()
        val vm = viewModel(store, FakeOutput())
        vm.setVoiceSource(com.aicarcompanion.app.data.local.VoiceSource.ONLINE); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(com.aicarcompanion.app.data.local.VoiceSource.ONLINE, store.settings.value.voiceSource)
        assertEquals(VoiceRequest.Gendered(VoiceGender.FEMALE, 0, preferOnline = true), VoiceConfigFactory.from(store.settings.value).voice)
    }

    @Test fun `reset restores only personalization`() {
        val store = InMemorySettingsStore(
            AppSettings(
                assistantProfileId = "ace", customName = "Zed", voiceChoice = VoiceChoice.Installed("v"), speechRate = 1.4f, speechPitch = 0.8f,
                explanationLevel = ExplanationLevel.DIY, demoDataEnabled = true, ttsEngine = "com.x",
            ),
        )
        viewModel(store, FakeOutput()).resetPersonalization()
        val s = store.settings.value
        assertEquals("penelope", s.assistantProfileId); assertNull(s.customName)
        assertEquals(VoiceChoice.MatchPersonality, s.voiceChoice)
        assertEquals(1.0f, s.speechRate); assertEquals(1.0f, s.speechPitch)
        assertEquals(ExplanationLevel.DIY, s.explanationLevel)
        assertTrue(s.demoDataEnabled)
        assertEquals("com.x", s.ttsEngine)
    }

    @Test fun `preview of the selected profile uses exactly the conversation config and its name`() {
        val store = InMemorySettingsStore(AppSettings(assistantProfileId = "luna", customName = "Zed", speechRate = 1.2f))
        val out = FakeOutput()
        val vm = viewModel(store, out)
        vm.preview("luna"); dispatcher.scheduler.advanceUntilIdle()
        val (text, config) = out.previews.single()
        assertEquals(VoiceConfigFactory.from(store.settings.value), config)
        assertTrue(text.startsWith("Hi, I'm Zed."))
    }

    @Test fun `preview of another tile uses that profile's voice with the current speed`() {
        val store = InMemorySettingsStore(AppSettings(assistantProfileId = "luna", speechRate = 1.2f))
        val out = FakeOutput()
        val vm = viewModel(store, out)
        vm.preview("ace"); dispatcher.scheduler.advanceUntilIdle()
        val (text, config) = out.previews.single()
        assertEquals(VoiceRequest.Gendered(VoiceGender.MALE, 3), config.voice)
        assertEquals(1.2f, config.rate)
        assertTrue(text.startsWith("Hi, I'm Ace."))
        // The saved setting is untouched by a preview.
        assertEquals("luna", store.settings.value.assistantProfileId)
    }

    @Test fun `online preview uses the same request as the conversation`() {
        val store = InMemorySettingsStore(AppSettings(assistantProfileId = "luna", voiceSource = com.aicarcompanion.app.data.local.VoiceSource.ONLINE))
        val out = FakeOutput()
        viewModel(store, out).preview(null); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(VoiceConfigFactory.from(store.settings.value), out.previews.single().second)
        assertTrue((out.previews.single().second.voice as VoiceRequest.Gendered).preferOnline)
    }

    @Test fun `a new preview stops the one that is playing`() {
        val out = FakeOutput()
        val vm = viewModel(InMemorySettingsStore(), out)
        vm.preview("ace"); vm.preview("nova"); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, out.stops)
        assertEquals(listOf("Hi, I'm Nova."), out.previews.map { it.first.substringBefore(" You") })
    }

    @Test fun `resolutions are loaded for all ten profiles`() {
        val vm = viewModel(InMemorySettingsStore(), FakeOutput())
        vm.loadVoices(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(10, vm.voiceResolutions.value.size)
        assertTrue(vm.voiceResolutions.value["oliver"] is VoiceResolution.FallbackDefault)
        assertTrue(vm.voiceResolutions.value["penelope"] is VoiceResolution.Voice)
    }
}
