package com.aicarcompanion.app.phase1b

import com.aicarcompanion.app.assistant.persona.VoiceChoice
import com.aicarcompanion.app.dashboard.DashboardItem
import com.aicarcompanion.app.dashboard.HomeLayout
import com.aicarcompanion.app.dashboard.LevelLayout
import com.aicarcompanion.app.data.local.AppSettings
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.MapKeyValueStore
import com.aicarcompanion.app.data.local.PersistentSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Test

/** Knowledge level and personality/voice are independent: switching levels never touches voice or personality settings. */
class KnowledgeLevelPersonalityIndependenceTest {
    private fun voiceFields(s: AppSettings) = listOf(
        s.assistantProfileId, s.customName, s.voiceChoice, s.speechRate, s.speechPitch, s.ttsEngine, s.continuousConversation,
        s.dashboard.forLevel(ExplanationLevel.EVERYDAY), s.dashboard.forLevel(ExplanationLevel.DIY),
    )

    @Test fun `toggling levels preserves personality, voice, speech and layouts, also after reload`() {
        val kv = MapKeyValueStore()
        val store = PersistentSettingsStore(kv)
        store.update {
            it.copy(
                assistantProfileId = "ace", customName = "Zoë", voiceChoice = VoiceChoice.Profile("max"),
                speechRate = 1.2f, speechPitch = 0.9f, ttsEngine = "com.example.tts", continuousConversation = false,
                dashboard = it.dashboard
                    .with(ExplanationLevel.DIY, LevelLayout(HomeLayout.CUSTOMIZABLE, listOf(DashboardItem.ADAPTER, DashboardItem.HISTORY))),
            )
        }
        val expected = voiceFields(store.settings.value)
        listOf(ExplanationLevel.EVERYDAY, ExplanationLevel.DIY, ExplanationLevel.EVERYDAY, ExplanationLevel.DIY).forEach { level ->
            store.update { it.copy(explanationLevel = level) }
            assertEquals(level, store.settings.value.explanationLevel)
            assertEquals(expected, voiceFields(store.settings.value))
            assertEquals(expected, voiceFields(PersistentSettingsStore(kv).settings.value))
        }
    }

    @Test fun `stored Advanced migrates to DIY with personality and voice intact`() {
        val kv = MapKeyValueStore()
        PersistentSettingsStore(kv).update {
            it.copy(assistantProfileId = "ace", customName = "Zoë", voiceChoice = VoiceChoice.Profile("max"), speechRate = 1.2f, continuousConversation = false)
        }
        kv.map["explanation_level"] = "ADVANCED"
        val s = PersistentSettingsStore(kv).settings.value
        assertEquals(ExplanationLevel.DIY, s.explanationLevel)
        assertEquals("ace", s.assistantProfileId)
        assertEquals("Zoë", s.customName)
        assertEquals(VoiceChoice.Profile("max"), s.voiceChoice)
        assertEquals(1.2f, s.speechRate)
        assertEquals(false, s.continuousConversation)
    }
}
