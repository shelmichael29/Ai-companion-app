package com.aicarcompanion.app.data.local

import com.aicarcompanion.app.assistant.persona.VoiceChoice
import com.aicarcompanion.app.dashboard.HomeLayout
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

    @Test fun `a newer schema is never treated as legacy and is not wiped`() {
        val kv = legacy("settings_schema" to "3", "assistant_profile" to "ace", "custom_assistant_name" to "Zed", "voice_choice" to "profile:luna")
        assertFalse(SettingsCodec.needsMigration(kv))
        val s = PersistentSettingsStore(kv).settings.value
        assertEquals("ace", s.assistantProfileId); assertEquals("Zed", s.customName)
        assertEquals(VoiceChoice.Profile("luna"), s.voiceChoice)
        assertEquals("Zed", kv.map["custom_assistant_name"])
    }

    @Test fun `settings and requests never print names or utterances`() {
        val s = AppSettings(customName = "Zed", lastAdapterAddress = "00:11:22:33:44:55", activeVehicleId = "car1")
        assertFalse(s.toString().contains("Zed")); assertFalse(s.toString().contains("00:11")); assertFalse(s.toString().contains("car1"))
        val r = com.aicarcompanion.app.assistant.conversation.AssistantRequest("call Mom about my VIN", "Zed")
        assertFalse(r.toString().contains("Mom")); assertFalse(r.toString().contains("Zed"))
    }

    @Test fun `knowledge level is untouched by personality changes`() {
        val kv = MapKeyValueStore()
        val store = PersistentSettingsStore(kv)
        store.update { it.copy(explanationLevel = ExplanationLevel.DIY) }
        store.update { it.copy(assistantProfileId = "luna") }
        assertTrue(PersistentSettingsStore(kv).settings.value.explanationLevel == ExplanationLevel.DIY)
    }

    // ---- Phase 1A: the retired Advanced level migrates to DIY ------------------------------------

    private class CountingStore : KeyValueStore {
        val inner = MapKeyValueStore()
        var writes = 0
        override fun getString(key: String) = inner.getString(key)
        override fun getFloat(key: String) = inner.getFloat(key)
        override fun getBoolean(key: String) = inner.getBoolean(key)
        override fun write(values: Map<String, Any?>) { writes++; inner.write(values) }
    }

    private fun current(vararg pairs: Pair<String, Any?>) =
        legacy("settings_schema" to "2", *pairs)

    @Test fun `exactly two levels are selectable and advanced maps to DIY`() {
        assertEquals(listOf(ExplanationLevel.EVERYDAY, ExplanationLevel.DIY), ExplanationLevel.selectable)
        assertEquals(ExplanationLevel.DIY, ExplanationLevel.ADVANCED.toSelectable())
        assertEquals(ExplanationLevel.EVERYDAY, ExplanationLevel.EVERYDAY.toSelectable())
        assertEquals(ExplanationLevel.DIY, ExplanationLevel.DIY.toSelectable())
    }

    @Test fun `stored advanced becomes DIY and is persisted`() {
        val kv = current("explanation_level" to "ADVANCED")
        assertTrue(SettingsCodec.needsLevelMigration(kv))
        assertEquals(ExplanationLevel.DIY, PersistentSettingsStore(kv).settings.value.explanationLevel)
        assertEquals("DIY", kv.map["explanation_level"])
        assertFalse(SettingsCodec.needsLevelMigration(kv))
    }

    @Test fun `level migration is idempotent and survives restart`() {
        val kv = CountingStore().apply { inner.map += mapOf("settings_schema" to "2", "explanation_level" to "ADVANCED") }
        PersistentSettingsStore(kv)
        val afterFirst = kv.inner.map.toMap()
        val writesAfterFirst = kv.writes
        assertEquals(ExplanationLevel.DIY, PersistentSettingsStore(kv).settings.value.explanationLevel)
        assertEquals(afterFirst, kv.inner.map.toMap())
        assertEquals(writesAfterFirst, kv.writes)
    }

    @Test fun `migration changes only the level key and keeps every other value`() {
        val kv = current(
            "explanation_level" to "ADVANCED", "assistant_profile" to "ace", "custom_assistant_name" to "Zed",
            "voice_choice" to "profile:luna", "active_vehicle_id" to "car1", "last_adapter_address" to "00:11:22:33:44:55",
            "speech_rate" to 1.2f, "demo_data_enabled" to true, "ai_enabled" to false, "theme_mode" to "LIGHT",
        )
        val before = kv.map.toMap()
        PersistentSettingsStore(kv)
        val after = kv.map.toMap()
        assertEquals(before.filterKeys { it != "explanation_level" }, after.filterKeys { it != "explanation_level" })
        assertEquals("DIY", after["explanation_level"])
    }

    @Test fun `a newer schema is kept when migrating the level`() {
        val kv = current("explanation_level" to "ADVANCED").also { it.map["settings_schema"] = "3" }
        PersistentSettingsStore(kv)
        assertEquals("3", kv.map["settings_schema"])
        assertEquals("DIY", kv.map["explanation_level"])
    }

    @Test fun `everyday and DIY users cause no writes at startup`() {
        listOf("EVERYDAY", "DIY").forEach { level ->
            val kv = CountingStore().apply { inner.map += mapOf("settings_schema" to "2", "explanation_level" to level) }
            val s = PersistentSettingsStore(kv).settings.value
            assertEquals(level, s.explanationLevel.name)
            assertEquals(level, 0, kv.writes)
        }
    }

    @Test fun `legacy settings with advanced migrate to DIY at schema 2`() {
        val kv = legacy("assistant_name" to "Jarvis", "explanation_level" to "ADVANCED")
        val s = PersistentSettingsStore(kv).settings.value
        assertEquals(ExplanationLevel.DIY, s.explanationLevel)
        assertEquals("Jarvis", s.customName)
        assertEquals("2", kv.map["settings_schema"])
        assertEquals("DIY", kv.map["explanation_level"])
    }

    @Test fun `unknown stored level falls back to everyday and is not rewritten`() {
        val kv = CountingStore().apply { inner.map += mapOf("settings_schema" to "2", "explanation_level" to "EXPERT") }
        assertEquals(ExplanationLevel.EVERYDAY, PersistentSettingsStore(kv).settings.value.explanationLevel)
        assertEquals(0, kv.writes)
    }

    @Test fun `advanced layout is copied to DIY only when DIY has none and advanced keys stay`() {
        val kv = current(
            "explanation_level" to "ADVANCED", "home_layout_advanced" to "CUSTOMIZABLE", "home_items_advanced" to "CAR_HEALTH,ADAPTER,EXTRAS",
        )
        val s = PersistentSettingsStore(kv).settings.value
        assertEquals(HomeLayout.CUSTOMIZABLE, s.dashboard.forLevel(ExplanationLevel.DIY).layout)
        assertEquals("CUSTOMIZABLE", kv.map["home_layout_diy"])
        assertEquals("CAR_HEALTH,ADAPTER,EXTRAS", kv.map["home_items_diy"])
        assertEquals("CUSTOMIZABLE", kv.map["home_layout_advanced"])
        assertEquals("CAR_HEALTH,ADAPTER,EXTRAS", kv.map["home_items_advanced"])
    }

    @Test fun `an existing DIY layout is never overwritten by the advanced one`() {
        val kv = current(
            "explanation_level" to "ADVANCED", "home_layout_advanced" to "CUSTOMIZABLE", "home_items_advanced" to "CAR_HEALTH,EXTRAS",
            "home_layout_diy" to "EASY",
        )
        val s = PersistentSettingsStore(kv).settings.value
        assertEquals(HomeLayout.EASY, s.dashboard.forLevel(ExplanationLevel.DIY).layout)
        assertEquals("EASY", kv.map["home_layout_diy"])
        assertNull(kv.map["home_items_diy"])
    }

    @Test fun `choosing advanced programmatically stores DIY`() {
        val kv = MapKeyValueStore()
        val store = PersistentSettingsStore(kv)
        store.update { it.copy(explanationLevel = ExplanationLevel.ADVANCED.toSelectable()) }
        assertEquals("DIY", kv.map["explanation_level"])
    }
}
