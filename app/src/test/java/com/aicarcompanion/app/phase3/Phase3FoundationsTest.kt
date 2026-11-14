package com.aicarcompanion.app.phase3

import com.aicarcompanion.app.assistant.conversation.AssistantIntent
import com.aicarcompanion.app.assistant.conversation.CommandParser
import com.aicarcompanion.app.assistant.voice.VoiceTuning
import com.aicarcompanion.app.data.local.AppSettings
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.MapKeyValueStore
import com.aicarcompanion.app.data.local.PersistentSettingsStore
import com.aicarcompanion.app.data.local.SettingsCodec
import com.aicarcompanion.app.diagnostics.dtc.GenericDtcCatalog
import com.aicarcompanion.app.diagnostics.obd.Elm327ResponseParser
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import com.aicarcompanion.app.diagnostics.smart.CauseConfidence
import com.aicarcompanion.app.diagnostics.smart.ConfirmationTest
import com.aicarcompanion.app.diagnostics.smart.DiagnosticDataPlan
import com.aicarcompanion.app.diagnostics.smart.PossibleCause
import com.aicarcompanion.app.diagnostics.smart.SymptomCategory
import com.aicarcompanion.app.diagnostics.smart.SymptomClassifier
import com.aicarcompanion.app.diagnostics.smart.SymptomGuide
import com.aicarcompanion.app.smartfix.RemedyCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale

class Phase3FoundationsTest {

    // --- Settings persistence ----------------------------------------------------------------

    @Test fun `settings round-trip through storage`() {
        val kv = MapKeyValueStore()
        val store = PersistentSettingsStore(kv)
        store.update {
            it.copy(
                assistantName = "Ada", speechRate = 0.9f, speechPitch = 0.95f, ttsEngine = "com.google.android.tts",
                ttsVoice = "en-us-x-iol-local", continuousConversation = false, explanationLevel = ExplanationLevel.DIY,
            )
        }
        assertEquals(store.settings.value, PersistentSettingsStore(kv).settings.value)
    }

    @Test fun `defaults are natural voice, continuous conversation, everyday level`() {
        val s = SettingsCodec.read(MapKeyValueStore())
        assertEquals(1.0f, s.speechRate)
        assertEquals(1.0f, s.speechPitch)
        assertTrue(s.continuousConversation)
        assertEquals(ExplanationLevel.EVERYDAY, s.explanationLevel)
        assertEquals("Penelope", s.assistantName)
    }

    @Test fun `out-of-range stored voice values are clamped`() {
        val kv = MapKeyValueStore().apply { map["speech_rate"] = 1.6f; map["speech_pitch"] = 2.0f; map["explanation_level"] = "BOGUS" }
        val s = SettingsCodec.read(kv)
        assertEquals(AppSettings.RATE_RANGE.endInclusive, s.speechRate)
        assertEquals(AppSettings.PITCH_RANGE.endInclusive, s.speechPitch)
        assertEquals(ExplanationLevel.EVERYDAY, s.explanationLevel)
    }

    // --- Voice tuning ------------------------------------------------------------------------

    @Test fun `rate and pitch are clamped independently`() {
        assertEquals(1.5f, VoiceTuning.rate(3f))
        assertEquals(1.0f, VoiceTuning.pitch(1.0f))
        assertEquals(0.75f, VoiceTuning.pitch(0.1f))
    }

    @Test fun `voice list prefers the user's language, offline and quality`() {
        val us = Locale.US
        val voices = listOf(
            VoiceTuning.RawVoice("de-1", Locale.GERMANY, false, 400, emptySet()),
            VoiceTuning.RawVoice("en-us-net", us, true, 500, emptySet()),
            VoiceTuning.RawVoice("en-us-local", us, false, 400, emptySet()),
            VoiceTuning.RawVoice("en-gb-local", Locale.UK, false, 400, emptySet()),
            VoiceTuning.RawVoice("en-us-missing", us, false, 500, setOf("notInstalled")),
        )
        val ids = VoiceTuning.voiceOptions(voices, us).map { it.id }
        assertEquals(listOf("en-us-local", "en-us-net", "en-gb-local"), ids)
        assertTrue(VoiceTuning.voiceOptions(voices, us).first().label.contains("works offline"))
    }

    @Test fun `long text is chunked at sentence boundaries`() {
        val chunks = VoiceTuning.chunk("One two. Three four. Five six.", 12)
        assertEquals(listOf("One two.", "Three four.", "Five six."), chunks)
        assertEquals(listOf("short"), VoiceTuning.chunk("short", 100))
        assertTrue(VoiceTuning.chunk("x".repeat(25), 10).all { it.length <= 10 })
    }

    // --- Parser: conversation and detail intents ---------------------------------------------

    @Test fun `conversation control and detail requests`() {
        val p = CommandParser { "Penelope" }
        assertEquals(AssistantIntent.EndConversation, p.parse("Goodbye!"))
        assertEquals(AssistantIntent.EndConversation, p.parse("Penelope, that's all"))
        assertEquals(AssistantIntent.PauseConversation, p.parse("pause"))
        assertEquals(AssistantIntent.Simpler, p.parse("Explain that more simply."))
        assertEquals(AssistantIntent.MoreTechnical, p.parse("Give me the technical explanation."))
        assertEquals(AssistantIntent.SensorReadings, p.parse("Show me the sensor readings."))
        assertEquals(AssistantIntent.Repeat, p.parse("can you say that again"))
        // "stop" inside a sentence is not a command.
        assertFalse(p.parse("my car stops when it's cold") == AssistantIntent.EndConversation)
    }

    @Test fun `everyday symptom phrases are recognized`() {
        val p = CommandParser { "Penelope" }
        fun cat(s: String) = (p.parse(s) as AssistantIntent.Symptom).category
        assertEquals(SymptomCategory.SHAKING, cat("Why is my car shaking?"))
        assertEquals(SymptomCategory.NOISE, cat("My car makes a weird noise when I turn."))
        assertEquals(SymptomCategory.POOR_FUEL_ECONOMY, cat("Why is my car using so much gas?"))
        assertEquals(SymptomCategory.LOSS_OF_POWER, cat("My car feels like it's losing power."))
        assertEquals(SymptomCategory.OVERHEATING, cat("I think my car is overheating"))
        assertEquals(SymptomCategory.FLASHING_CHECK_ENGINE, cat("my check engine light is flashing"))
        // Existing commands still win where they should.
        assertEquals(AssistantIntent.ReadTroubleCodes, p.parse("read my trouble codes"))
        assertTrue(p.parse("what's my coolant temperature") is AssistantIntent.LiveValue)
    }

    @Test fun `urgent categories have safety advice, common ones have follow-ups`() {
        SymptomCategory.entries.filter { it.isUrgent }.forEach { assertTrue(it.name, SymptomGuide.safetyAdvice(it) != null) }
        listOf(SymptomCategory.SHAKING, SymptomCategory.NOISE, SymptomCategory.POOR_FUEL_ECONOMY, SymptomCategory.LOSS_OF_POWER, SymptomCategory.HARD_START)
            .forEach { assertTrue(it.name, SymptomGuide.followUp(it) != null) }
        assertNull(SymptomClassifier.classify("tell me about my engine"))
    }

    // --- Smart Diagnostics / SmartFix invariants ----------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun `a cause cannot be confirmed without a test result`() {
        PossibleCause("Vacuum leak", "Air getting in", CauseConfidence.CONFIRMED, confirmationTests = listOf(ConfirmationTest("Smoke test", null, null)))
    }

    @Test fun `a cause can be confirmed with a recorded test`() {
        val c = PossibleCause(
            "Vacuum leak", "Air getting in", CauseConfidence.CONFIRMED,
            confirmationTests = listOf(ConfirmationTest("Smoke test", "Leak at intake boot", Instant.EPOCH)),
        )
        assertEquals(CauseConfidence.CONFIRMED, c.confidence)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a SmartFix remedy must cite a source`() {
        RemedyCandidate("Replace coil", "Coil failure", listOf("Swap coil"), sources = emptyList())
    }

    @Test fun `data plan asks for fuel trims when fuel economy is poor`() {
        val pids = DiagnosticDataPlan.forCategory(SymptomCategory.POOR_FUEL_ECONOMY).mapNotNull { it.pid }
        assertTrue(pids.containsAll(listOf(ObdPid.LONG_TERM_FUEL_TRIM_B1, ObdPid.MAF_RATE)))
    }

    // --- Catalog and new PIDs -----------------------------------------------------------------

    @Test fun `catalog has all three explanation levels`() {
        listOf("P0171", "P0300", "P0302", "P0420", "P0442", "P0128", "P0101").forEach { code ->
            val i = GenericDtcCatalog.lookup(code)!!
            assertTrue(code, i.simple.isNotBlank() && i.diyChecks.isNotBlank() && i.technical.isNotBlank())
        }
        assertFalse(GenericDtcCatalog.lookup("P0171")!!.simple.contains("trim"))
    }

    @Test fun `fuel trim, MAF and MAP decoding`() {
        assertEquals(0.0, Elm327ResponseParser.decodePid("41 07 80", ObdPid.LONG_TERM_FUEL_TRIM_B1)!!, 0.001)
        assertEquals(10.156, Elm327ResponseParser.decodePid("41 07 8D", ObdPid.LONG_TERM_FUEL_TRIM_B1)!!, 0.001)
        assertEquals(-25.0, Elm327ResponseParser.decodePid("41 06 60", ObdPid.SHORT_TERM_FUEL_TRIM_B1)!!, 0.001)
        assertEquals(3.5, Elm327ResponseParser.decodePid("41 10 01 5E", ObdPid.MAF_RATE)!!, 0.001)
        assertEquals(33.0, Elm327ResponseParser.decodePid("41 0B 21", ObdPid.INTAKE_MAP)!!, 0.001)
    }

    @Test fun `beginners see only the everyday readings`() {
        val everyday = ObdPid.entries.filter { it.visibleAt(ExplanationLevel.EVERYDAY) }
        assertEquals(setOf(ObdPid.COOLANT_TEMP, ObdPid.ENGINE_RPM, ObdPid.VEHICLE_SPEED, ObdPid.FUEL_LEVEL, ObdPid.CONTROL_MODULE_VOLTAGE), everyday.toSet())
        assertTrue(ObdPid.entries.all { it.visibleAt(ExplanationLevel.ADVANCED) })
    }
}
