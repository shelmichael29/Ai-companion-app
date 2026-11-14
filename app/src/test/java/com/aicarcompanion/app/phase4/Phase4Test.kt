package com.aicarcompanion.app.phase4

import com.aicarcompanion.app.assistant.VoiceSession
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.assistant.conversation.AssistantIntent
import com.aicarcompanion.app.assistant.conversation.AssistantReply
import com.aicarcompanion.app.assistant.conversation.AssistantRequest
import com.aicarcompanion.app.assistant.conversation.CommandParser
import com.aicarcompanion.app.assistant.conversation.ConversationManager
import com.aicarcompanion.app.assistant.conversation.NoAdapterDiagnosticCommandHandler
import com.aicarcompanion.app.assistant.conversation.ReasoningProvider
import com.aicarcompanion.app.assistant.conversation.ReplyBasis
import com.aicarcompanion.app.assistant.conversation.RuleBasedReasoningProvider
import com.aicarcompanion.app.assistant.conversation.VehicleContext
import com.aicarcompanion.app.assistant.voice.SpeakResult
import com.aicarcompanion.app.assistant.voice.SpeechInput
import com.aicarcompanion.app.assistant.voice.SpeechInputEvent
import com.aicarcompanion.app.assistant.voice.SpeechOutput
import com.aicarcompanion.app.assistant.voice.SpeechOutputState
import com.aicarcompanion.app.assistant.voice.VoiceConfig
import com.aicarcompanion.app.assistant.voice.normalizeRms
import com.aicarcompanion.app.data.local.MapKeyValueStore
import com.aicarcompanion.app.data.local.PersistentSettingsStore
import com.aicarcompanion.app.data.local.ThemeMode
import com.aicarcompanion.app.data.sample.SampleData
import com.aicarcompanion.app.diagnostics.dtc.GenericDtcCatalog
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdTransport
import com.aicarcompanion.app.diagnostics.smart.RepairGlossary
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DiagnosticTroubleCode
import com.aicarcompanion.app.domain.model.DtcSeverity
import com.aicarcompanion.app.domain.model.DtcStatus
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.RecordedTroubleCode
import com.aicarcompanion.app.domain.model.TroubleCodeRead
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.ui.design.OrbMode
import com.aicarcompanion.app.ui.home.connectionSummary
import com.aicarcompanion.app.ui.home.greetingFor
import com.aicarcompanion.app.ui.home.healthSummary
import com.aicarcompanion.app.ui.results.DiagnosticPresentation
import com.aicarcompanion.app.ui.results.UrgencyLevel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalTime

class Phase4Test {

    // --- Home ---------------------------------------------------------------------------------

    @Test fun `greeting follows the time of day`() {
        assertEquals("Good morning!", greetingFor(LocalTime.of(8, 0)))
        assertEquals("Good afternoon!", greetingFor(LocalTime.of(13, 30)))
        assertEquals("Good evening!", greetingFor(LocalTime.of(19, 0)))
        assertEquals("Hi there!", greetingFor(LocalTime.of(2, 0)))
    }

    @Test fun `health summary never claims all good without a real read`() {
        assertEquals("Car health not checked yet", healthSummary(emptyList(), null).title)
        val read = TroubleCodeRead("s", "v", Instant.EPOCH, DataOrigin.OBD_BLUETOOTH, emptyList())
        assertEquals("No warning codes found", healthSummary(emptyList(), read).title)
        val sample = healthSummary(SampleData.troubleCodes(), null)
        assertTrue(sample.hasIssues)
        assertTrue(sample.subtitle.contains("Sample data"))
    }

    @Test fun `connection is only claimed when confirmed`() {
        assertEquals("Not connected" to false, connectionSummary(ObdConnectionState.Disconnected))
        assertFalse(connectionSummary(ObdConnectionState.Connecting(ObdTransport.BLUETOOTH_CLASSIC)).second)
        assertFalse(connectionSummary(ObdConnectionState.Reconnecting("OBDII", 1, 3)).second)
        assertEquals("Connected to your car" to true, connectionSummary(ObdConnectionState.Connected(ObdTransport.BLUETOOTH_CLASSIC, "OBDII", "CAN")))
        assertTrue(connectionSummary(ObdConnectionState.Connected(ObdTransport.BLUETOOTH_CLASSIC, "OBDII", null)).first.contains("not responding"))
    }

    // --- Orb ----------------------------------------------------------------------------------

    @Test fun `orb mode comes only from real voice state`() {
        assertEquals(OrbMode.IDLE, OrbMode.from(VoiceState.Idle))
        assertEquals(OrbMode.LISTENING, OrbMode.from(VoiceState.Listening("hi")))
        assertEquals(OrbMode.THINKING, OrbMode.from(VoiceState.Processing("hi")))
        assertEquals(OrbMode.SPEAKING, OrbMode.from(VoiceState.Speaking(AssistantReply("x", ReplyBasis.CONVERSATION))))
        assertEquals(OrbMode.PAUSED, OrbMode.from(VoiceState.Paused("p")))
        assertEquals(OrbMode.ERROR, OrbMode.from(VoiceState.Error("e")))
    }

    @Test fun `microphone level reaches the orb and resets after listening`() = runTest {
        val channel = Channel<SpeechInputEvent>(Channel.UNLIMITED)
        val input = object : SpeechInput {
            override val isAvailable = true
            override fun listen(): Flow<SpeechInputEvent> = channel.receiveAsFlow()
            override fun finishListening() = Unit
        }
        val output = object : SpeechOutput {
            override val state = MutableStateFlow<SpeechOutputState>(SpeechOutputState.Ready)
            override suspend fun speak(text: String) = SpeakResult.Completed
            override fun stop() = Unit
            override fun configure(config: VoiceConfig) = Unit
        }
        val echo = object : ReasoningProvider {
            override suspend fun respond(request: AssistantRequest) = AssistantReply("ok", ReplyBasis.CONVERSATION)
        }
        val s = VoiceSession(this, input, output, ConversationManager(echo, { "Penelope" }), continuousEnabled = { false })
        s.start(); advanceUntilIdle()
        channel.send(SpeechInputEvent.Level(0.8f)); advanceUntilIdle()
        assertEquals(0.8f, s.inputLevel.value)
        channel.send(SpeechInputEvent.Final("hello")); advanceUntilIdle()
        assertEquals(0f, s.inputLevel.value)
        assertEquals(VoiceState.Idle, s.state.value)
        assertEquals(0f, normalizeRms(-10f))
        assertEquals(1f, normalizeRms(20f))
    }

    // --- Diagnostic results -------------------------------------------------------------------

    @Test fun `result card for a known code`() {
        val info = GenericDtcCatalog.lookup("P0300")!!
        val dtc = DiagnosticTroubleCode("P0300", info.description, info.simple, info.diyChecks, info.technical, DtcStatus.STORED, info.severity, DataOrigin.OBD_BLUETOOTH)
        val r = DiagnosticPresentation.fromCode(dtc, "checked today")
        assertEquals(UrgencyLevel.HIGH, r.urgency)
        assertTrue(r.title, r.title.startsWith("Engine is misfiring"))
        assertFalse(r.isSample)
        assertTrue(r.nextSteps.any { it.contains("as soon as you can") })
        assertTrue(r.details.any { it.first == "Code" && it.second == "P0300" })
        assertTrue(r.details.any { it.first == "Source" && it.second.contains("checked today") })
    }

    @Test fun `unknown code is not dressed up as a known diagnosis`() {
        val dtc = DiagnosticTroubleCode("P1999", "Powertrain code", null, null, null, DtcStatus.STORED, DtcSeverity.UNKNOWN, DataOrigin.OBD_BLUETOOTH)
        val r = DiagnosticPresentation.fromCode(dtc, null)
        assertEquals(UrgencyLevel.UNKNOWN, r.urgency)
        assertTrue(r.whatsHappening.contains("don't have a plain-language explanation"))
        assertTrue(r.nextSteps.first().contains("don't have details"))
    }

    @Test fun `sample codes are flagged`() {
        val r = DiagnosticPresentation.fromCode(SampleData.troubleCodes().first(), null)
        assertTrue(r.isSample)
        assertTrue(r.sourceLabel.contains("not from your car"))
    }

    // --- Suggested questions -------------------------------------------------------------------

    private val bmw = Vehicle("bmw", 2011, "BMW", "528i", engine = EngineSpec(3.0, 6))
    private fun provider(read: TroubleCodeRead? = null, demo: Boolean = false) = RuleBasedReasoningProvider(
        CommandParser { "Penelope" },
        { VehicleContext(bmw, read, emptyList(), ObdConnectionState.Disconnected, if (demo) SampleData.troubleCodes() else emptyList()) },
        NoAdapterDiagnosticCommandHandler, GenericDtcCatalog,
    )
    private suspend fun RuleBasedReasoningProvider.ask(text: String) = respond(AssistantRequest(text, "Penelope"))

    @Test fun `home suggestions route to real handlers`() {
        val p = CommandParser { "Penelope" }
        assertTrue(p.parse("My car is shaking.") is AssistantIntent.Symptom)
        assertTrue(p.parse("What does this warning light mean?") is AssistantIntent.Symptom)
        assertEquals(AssistantIntent.CanIDrive, p.parse("Can I still drive my car?"))
        assertEquals(AssistantIntent.RepairHelp(null), p.parse("Help me understand a repair."))
        assertEquals(AssistantIntent.RepairHelp("brake pads"), p.parse("The shop said I need brake pads, what is that?"))
    }

    @Test fun `warning light follow-up`() = runTest {
        val p = provider()
        assertTrue(p.ask("What does this warning light mean?").text.contains("What color is the light"))
        assertTrue(p.ask("it looks like an oil can").text.contains("oil pressure warning"))
    }

    @Test fun `can I drive answers honestly with and without data`() = runTest {
        val noData = provider().ask("Can I still drive my car?")
        assertTrue(noData.text.contains("can't tell for sure"))
        val at = Instant.parse("2026-11-01T15:00:00Z")
        val read = TroubleCodeRead("s", "bmw", at, DataOrigin.OBD_BLUETOOTH,
            listOf(RecordedTroubleCode(1, "bmw", "P0300", DtcStatus.STORED, at, "s", DataOrigin.OBD_BLUETOOTH)))
        val withCodes = provider(read).ask("is it safe to drive")
        assertEquals(ReplyBasis.RECORDED_DATA, withCodes.basis)
        assertTrue(withCodes.text.contains("Based on the codes I last read"))
        assertTrue(withCodes.text.contains("This one is important"))
        assertEquals(ReplyBasis.SAMPLE_DATA, provider(demo = true).ask("can I drive").basis)
    }

    @Test fun `repair help explains an estimate item without prices`() = runTest {
        val p = provider()
        assertTrue(p.ask("Help me understand a repair.").text.contains("Read me one item"))
        val r = p.ask("front brake pads and rotors")
        assertTrue(r.text, r.text.contains("Rotors are the metal discs") || r.text.contains("Brake pads are"))
        assertFalse(r.text.contains("$"))
        assertEquals("valve cover gasket", RepairGlossary.find("replace valve cover gasket")!!.name)
        assertEquals("ignition coil", RepairGlossary.find("ignition coil on cylinder 3")!!.name)
    }

    // --- Settings -----------------------------------------------------------------------------

    @Test fun `appearance settings persist`() {
        val kv = MapKeyValueStore()
        PersistentSettingsStore(kv).update { it.copy(themeMode = ThemeMode.DARK, reduceMotion = true) }
        val s = PersistentSettingsStore(kv).settings.value
        assertEquals(ThemeMode.DARK, s.themeMode)
        assertTrue(s.reduceMotion)
        assertEquals(ThemeMode.SYSTEM, PersistentSettingsStore(MapKeyValueStore()).settings.value.themeMode)
    }
}
