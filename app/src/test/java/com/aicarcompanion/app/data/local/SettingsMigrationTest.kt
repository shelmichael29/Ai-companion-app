package com.aicarcompanion.app.data.local

import com.aicarcompanion.app.assistant.persona.VoiceChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMigrationTest {
    private fun legacy(vararg pairs: Pair<String, Any?>) = MapKeyValueStore().apply { pairs.forEach { map[it.first] = it.second } }

    @Test fun `legacy custom name becomes a custom name on Penelope`() {
        val s = PersistentSettingsStore(legacy("assistant_name" to "Jarvis")).settings.value
        assertEquals("penelope", s.assistantProfileId)
        assertEquals("Jarvis", s.customName)
        assertEquals("Jarvis", s.assistantName)
    }

    @Test fun `legacy default blank or control-only names give no custom name`() {
        listOf("Penelope", "penelope", "  ", "\n​").forEach {
            val s = SettingsCodec.read(legacy("assistant_name" to it))
            assertNull(it, s.customName)
            assertEquals("Penelope", s.assistantName)
        }
    }

    @Test fun `legacy voice becomes an installed voice choice`() {
        val s = SettingsCodec.read(legacy("tts_voice" to "en-us-x-iol-local"))
        assertEquals(VoiceChoice.Installed("en-us-x-iol-local"), s.voiceChoice)
        assertEquals(VoiceChoice.MatchPersonality, SettingsCodec.read(legacy()).voiceChoice)
    }

    @Test fun `rate pitch and engine carry over and are clamped`() {
        val s = SettingsCodec.read(legacy("speech_rate" to 9f, "speech_pitch" to 0.1f, "tts_engine" to "com.example.tts"))
        assertEquals(AppSettings.RATE_RANGE.endInclusive, s.speechRate)
        assertEquals(AppSettings.PITCH_RANGE.start, s.speechPitch)
        assertEquals("com.example.tts", s.ttsEngine)
    }

    @Test fun `migration rewrites storage with schema 2 and removes legacy keys`() {
        val kv = legacy("assistant_name" to "Jarvis", "tts_voice" to "v1")
        PersistentSettingsStore(kv)
        assertEquals("2", kv.map["settings_schema"])
        assertFalse(kv.map.containsKey("assistant_name"))
        assertFalse(kv.map.containsKey("tts_voice"))
        assertEquals("Jarvis", kv.map["custom_assistant_name"])
        assertEquals("installed:v1", kv.map["voice_choice"])
        assertEquals("penelope", kv.map["assistant_profile"])
    }

    @Test fun `migration is idempotent`() {
        val kv = legacy("assistant_name" to "Jarvis", "tts_voice" to "v1")
        val first = PersistentSettingsStore(kv).settings.value
        val snapshot = kv.map.toMap()
        val second = PersistentSettingsStore(kv).settings.value
        assertEquals(first, second)
        assertEquals(snapshot, kv.map.toMap())
        assertFalse(SettingsCodec.needsMigration(kv))
    }

    @Test fun `corrupt new values fall back safely`() {
        val kv = legacy(
            "settings_schema" to "2", "assistant_profile" to "gone", "voice_choice" to "weird", "custom_assistant_name" to "\n\n",
        )
        val s = SettingsCodec.read(kv)
        assertEquals("penelope", s.assistantProfileId)
        assertEquals(VoiceChoice.MatchPersonality, s.voiceChoice)
        assertNull(s.customName)
        assertEquals(VoiceChoice.MatchPersonality, SettingsCodec.read(legacy("settings_schema" to "2", "voice_choice" to "profile:zzz")).voiceChoice)
        assertEquals(VoiceChoice.MatchPersonality, SettingsCodec.read(legacy("settings_schema" to "2", "voice_choice" to "installed:")).voiceChoice)
    }

    @Test fun `all voice choices and profile round-trip`() {
        listOf(VoiceChoice.MatchPersonality, VoiceChoice.Profile("max"), VoiceChoice.Installed("en-gb-x-gbb-local")).forEach { choice ->
            val kv = MapKeyValueStore()
            val store = PersistentSettingsStore(kv)
            store.update { it.copy(assistantProfileId = "ace", customName = "Zoë", voiceChoice = choice) }
            val back = PersistentSettingsStore(kv).settings.value
            assertEquals(choice, back.voiceChoice)
            assertEquals("ace", back.assistantProfileId)
            assertEquals("Zoë", back.assistantName)
        }
    }

    @Test fun `knowledge level is untouched by personality changes`() {
        val kv = MapKeyValueStore()
        val store = PersistentSettingsStore(kv)
        store.update { it.copy(explanationLevel = ExplanationLevel.ADVANCED) }
        store.update { it.copy(assistantProfileId = "luna") }
        assertTrue(PersistentSettingsStore(kv).settings.value.explanationLevel == ExplanationLevel.ADVANCED)
    }
}
