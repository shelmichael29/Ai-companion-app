package com.aicarcompanion.app.ai

import com.aicarcompanion.app.assistant.VoiceSession
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.assistant.conversation.AssistantRequest
import com.aicarcompanion.app.assistant.conversation.CommandParser
import com.aicarcompanion.app.assistant.conversation.ConversationManager
import com.aicarcompanion.app.assistant.conversation.NoAdapterDiagnosticCommandHandler
import com.aicarcompanion.app.assistant.conversation.ReplyBasis
import com.aicarcompanion.app.assistant.conversation.RuleBasedReasoningProvider
import com.aicarcompanion.app.assistant.conversation.VehicleContext
import com.aicarcompanion.app.assistant.voice.SpeakResult
import com.aicarcompanion.app.assistant.voice.SpeechInput
import com.aicarcompanion.app.assistant.voice.SpeechInputEvent
import com.aicarcompanion.app.assistant.voice.SpeechOutput
import com.aicarcompanion.app.assistant.voice.SpeechOutputState
import com.aicarcompanion.app.assistant.voice.VoiceConfig
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.MapKeyValueStore
import com.aicarcompanion.app.data.local.PersistentSettingsStore
import com.aicarcompanion.app.data.sample.SampleData
import com.aicarcompanion.app.diagnostics.dtc.GenericDtcCatalog
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DiagnosticObservation
import com.aicarcompanion.app.domain.model.DtcStatus
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.ObservationKind
import com.aicarcompanion.app.domain.model.RecordedTroubleCode
import com.aicarcompanion.app.domain.model.TroubleCodeRead
import com.aicarcompanion.app.domain.model.Vehicle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GeminiIntegrationTest {

    /** Fake model: records every call; replies with a scripted answer or failure. */
    private class FakeClient(var configured: Boolean = true) : AiChatClient {
        data class Call(val system: String, val history: List<AiTurn>, val message: String)
        val calls = mutableListOf<Call>()
        var next: (String) -> AiResult = { AiResult.Reply("AI says: $it", "gemini-3.8-flash") }
        override val isConfigured get() = configured
        override val modelName = "gemini-3.8-flash"
        override suspend fun generate(systemInstruction: String, history: List<AiTurn>, message: String): AiResult {
            calls += Call(systemInstruction, history, message)
            return next(message)
        }
    }

    private val bmw = Vehicle(
        id = "bmw", year = 2011, make = "BMW", model = "528i", trim = "F10",
        engine = EngineSpec(3.0, 6, EngineLayout.INLINE, engineCode = "N52B30", fuelGrade = "Premium"),
        vin = "WBAFR1C56BC000001", odometerMiles = 98_000, nickname = "Sheldon's daily",
    )
    private val read = TroubleCodeRead(
        "s", "bmw", Instant.parse("2026-11-01T15:00:00Z"), DataOrigin.OBD_BLUETOOTH,
        listOf(RecordedTroubleCode(1, "bmw", "P0171", DtcStatus.STORED, Instant.EPOCH, "s", DataOrigin.OBD_BLUETOOTH)),
    )
    private val saved = mutableListOf<DiagnosticObservation>()

    private fun ctx(demo: Boolean = false) =
        VehicleContext(bmw, read, emptyList(), ObdConnectionState.Disconnected, if (demo) SampleData.troubleCodes() else emptyList(), listOf("rough idle when cold"))

    private fun provider(client: FakeClient, enabled: Boolean = true, demo: Boolean = false): GeminiReasoningProvider {
        val parser = CommandParser { "Penelope" }
        val c = ctx(demo)
        val rules = RuleBasedReasoningProvider(parser, { c }, NoAdapterDiagnosticCommandHandler, GenericDtcCatalog)
        return GeminiReasoningProvider(parser, rules, { c }, client, GenericDtcCatalog, { enabled }, { saved += it }, now = { Instant.parse("2026-11-15T12:00:00Z") })
    }

    private suspend fun GeminiReasoningProvider.ask(text: String, level: ExplanationLevel = ExplanationLevel.EVERYDAY) =
        respond(AssistantRequest(text, "Penelope", level))

    // --- Routing --------------------------------------------------------------------------------

    @Test fun `open conversation goes to Gemini and is labeled as AI`() = runTest {
        val client = FakeClient()
        val r = provider(client).ask("Is a BMW 528i expensive to maintain?")
        assertEquals(1, client.calls.size)
        assertEquals(ReplyBasis.AI_EXPLANATION, r.basis)
        assertTrue(r.text.startsWith("AI says"))
    }

    @Test fun `safety-critical and real-data requests never reach Gemini`() = runTest {
        val client = FakeClient()
        val p = provider(client)
        assertTrue(p.ask("My car is overheating and there's steam").text.startsWith("Safety first"))
        p.ask("what's my coolant temperature")
        p.ask("read my trouble codes")
        p.ask("can I still drive my car")
        p.ask("explain P0171")
        p.ask("are you connected to the adapter")
        assertEquals("AI must not be called for safety or data requests", 0, client.calls.size)
    }

    @Test fun `general questions using data words still get real conversation`() {
        val p = CommandParser { "Penelope" }
        fun route(s: String) = AiRouting.route(p.parse(s), p.normalize(s))
        assertEquals(AiRouting.Route.AI, route("How long does a car battery usually last?"))
        assertEquals(AiRouting.Route.DETERMINISTIC, route("What's my battery voltage?"))
        assertEquals(AiRouting.Route.AI, route("What are trouble codes anyway?"))
        assertEquals(AiRouting.Route.DETERMINISTIC, route("Why is my check engine light on?"))
        assertEquals(AiRouting.Route.AI, route("My car makes a weird noise when I turn"))
        assertEquals(AiRouting.Route.DETERMINISTIC, route("I smell burning"))
    }

    // --- Context ----------------------------------------------------------------------------------

    @Test fun `multi-turn history carries both AI and app answers`() = runTest {
        val client = FakeClient()
        val p = provider(client)
        p.ask("Hi Penelope")
        p.ask("explain P0171") // deterministic, still remembered
        p.ask("What should I do about it?")
        val last = client.calls.last()
        assertEquals(4, last.history.size)
        assertTrue(last.history.any { it.role == AiTurn.Role.MODEL && it.text.contains("more air than expected") })
        assertEquals("What should I do about it?", last.message)
    }

    @Test fun `reset clears the AI's memory`() = runTest {
        val client = FakeClient()
        val p = provider(client)
        p.ask("Hi Penelope"); p.ask("Tell me something about my car")
        p.resetContext()
        p.ask("Hello again")
        assertTrue(client.calls.last().history.isEmpty())
    }

    @Test fun `grounding includes verified facts and excludes VIN and nickname`() = runTest {
        val client = FakeClient()
        provider(client).ask("What do you know about my car?", ExplanationLevel.DIY)
        val sys = client.calls.single().system
        assertTrue(sys.contains("2011 BMW 528i F10"))
        assertTrue(sys.contains("6 cylinders (inline (straight))"))
        assertTrue(sys.contains("P0171 (stored) verified meaning: System too lean (bank 1)"))
        assertTrue(sys.contains("98000 miles (last entered by the owner, not live)"))
        assertTrue(sys.contains("rough idle when cold"))
        assertTrue(sys.contains("DIY ENTHUSIAST"))
        assertTrue(sys.contains("Never state live readings"))
        assertFalse("VIN must not be sent", sys.contains("WBAFR1C56BC000001"))
        assertFalse("nickname must not be sent", sys.contains("Sheldon"))
    }

    @Test fun `demo codes are labeled as sample in the grounding`() = runTest {
        val client = FakeClient()
        provider(client, demo = true).ask("Tell me about my car's health")
        assertTrue(client.calls.single().system.contains("DEMO MODE: these are SAMPLE codes, NOT from the user's car"))
    }

    @Test fun `non-urgent symptoms are saved and answered by Gemini`() = runTest {
        val client = FakeClient()
        saved.clear()
        val r = provider(client).ask("My car shakes a little at stop lights")
        assertEquals(ReplyBasis.AI_EXPLANATION, r.basis)
        assertEquals(ObservationKind.SYMPTOM, saved.single().kind)
    }

    // --- Guards -------------------------------------------------------------------------------

    @Test fun `invented codes or readings are replaced by verified answers`() = runTest {
        val client = FakeClient().apply { next = { AiResult.Reply("You probably also have code P0300 stored.", "m") } }
        val r = provider(client).ask("Anything else wrong with my car?")
        assertTrue(r.basis != ReplyBasis.AI_EXPLANATION)
        assertFalse(r.text.contains("P0300"))

        val client2 = FakeClient().apply { next = { AiResult.Reply("Your engine is running at 230 degrees right now.", "m") } }
        assertTrue(provider(client2).ask("How's the engine doing?").basis != ReplyBasis.AI_EXPLANATION)
    }

    @Test fun `reply guard details`() {
        assertEquals("Hello there. Check your gas cap first.", ReplyGuard.clean("**Hello** there.\n\n- Check your *gas cap* first."))
        assertNull(ReplyGuard.check("A normal engine usually runs between 195 and 220 degrees.", emptySet()))
        assertNull(ReplyGuard.check("Your P0171 means the engine is lean.", setOf("P0171")))
        assertEquals(ReplyGuard.Violation.INVENTED_CODE, ReplyGuard.check("That's code P0420.", setOf("P0171")))
        assertEquals(ReplyGuard.Violation.UNVERIFIED_READING, ReplyGuard.check("Your battery is at 11.8 volts.", emptySet()))
        assertEquals(ReplyGuard.Violation.EMPTY, ReplyGuard.check(" ", emptySet()))
        assertTrue(ReplyGuard.clean("A. ".repeat(500)).length <= 700)
    }

    // --- Failures, settings, availability --------------------------------------------------------

    @Test fun `failures fall back with one clear notice and an unavailable status`() = runTest {
        val client = FakeClient().apply { next = { AiResult.Failure(AiFailure.OFFLINE) } }
        val p = provider(client)
        val first = p.ask("Is premium fuel really necessary?")
        assertTrue(first.text.startsWith("I can't reach my smarter answers right now (no internet connection)"))
        assertEquals(AiStatus.Unavailable(AiFailure.OFFLINE), p.status.value)
        val second = p.ask("What about synthetic oil?")
        assertFalse(second.text.contains("can't reach"))
        client.next = { AiResult.Reply("Synthetic oil is fine.", "gemini-3.8-flash") }
        assertEquals("Synthetic oil is fine.", p.ask("And synthetic oil?").text)
        assertEquals(AiStatus.Ready("gemini-3.8-flash"), p.status.value)
    }

    @Test fun `user can turn AI off and missing config is reported`() = runTest {
        val off = FakeClient()
        val p = provider(off, enabled = false)
        p.ask("Is premium fuel really necessary?")
        assertEquals(0, off.calls.size)
        assertEquals(AiStatus.Off, p.status.value)

        val missing = FakeClient(configured = false)
        val q = provider(missing)
        q.ask("Is premium fuel really necessary?")
        assertEquals(0, missing.calls.size)
        assertEquals(AiStatus.NotConfigured, q.status.value)
    }

    @Test fun `repeat returns the last answer without a new request`() = runTest {
        val client = FakeClient()
        val p = provider(client)
        val a = p.ask("Is premium fuel really necessary?")
        assertEquals(a, p.ask("say that again"))
        assertEquals(1, client.calls.size)
    }

    @Test fun `spoken question goes through Gemini and the answer is spoken`() = runTest {
        val channel = Channel<SpeechInputEvent>(Channel.UNLIMITED)
        val input = object : SpeechInput {
            override val isAvailable = true
            override fun listen(): Flow<SpeechInputEvent> = channel.receiveAsFlow()
            override fun finishListening() = Unit
        }
        val spoken = mutableListOf<String>()
        val output = object : SpeechOutput {
            override val state = MutableStateFlow<SpeechOutputState>(SpeechOutputState.Ready)
            override suspend fun speak(text: String): SpeakResult { spoken += text; return SpeakResult.Completed }
            override fun stop() = Unit
            override fun configure(config: VoiceConfig) = Unit
        }
        val client = FakeClient().apply { next = { AiResult.Reply("**Good question!** Premium fuel is recommended for your BMW.", "gemini-3.8-flash") } }
        val session = VoiceSession(this, input, output, ConversationManager(provider(client), { "Penelope" }), continuousEnabled = { false })
        session.start(); advanceUntilIdle()
        channel.send(SpeechInputEvent.Final("Do I really need premium fuel?")); advanceUntilIdle()
        assertEquals(listOf("Good question! Premium fuel is recommended for your BMW."), spoken)
        assertEquals("Do I really need premium fuel?", client.calls.single().message)
        assertEquals(VoiceState.Idle, session.state.value)
    }

    // --- Model selection and error mapping --------------------------------------------------------

    @Test fun `model selection accepts only stable Flash models`() {
        assertEquals("gemini-3.8-flash", ModelSelection.choose(null))
        assertEquals("gemini-3.5-flash-lite", ModelSelection.choose(" gemini-3.5-flash-lite "))
        listOf("gemini-2.5-flash", "gemini-3-flash-preview", "gemini-flash-latest", "gemini-4-argon", "gpt-4", "").forEach {
            assertEquals(it, "gemini-3.8-flash", ModelSelection.choose(it))
        }
    }

    @Test fun `error classification`() {
        // A 429 with no detail is treated as temporary; only an explicit daily quota is QUOTA
        // (see TemporaryAiErrorsRegressionTest).
        assertEquals(AiFailure.BUSY, AiErrorClassifier.classify("QuotaExceededException", null, false))
        assertEquals(AiFailure.TIMEOUT, AiErrorClassifier.classify("RequestTimeoutException", null, false))
        assertEquals(AiFailure.SERVICE_DISABLED, AiErrorClassifier.classify("ServiceDisabledException", null, false))
        assertEquals(AiFailure.APP_CHECK, AiErrorClassifier.classify("ServerException", "Firebase App Check token is invalid.", false))
        assertEquals(AiFailure.APP_CHECK, AiErrorClassifier.classify("ServerException", "HTTP 401 Unauthorized", false))
        assertEquals(AiFailure.MODEL_UNAVAILABLE, AiErrorClassifier.classify("ServerException", "models/gemini-x is not found (404)", false))
        assertEquals(AiFailure.OFFLINE, AiErrorClassifier.classify("UnknownException", "Something went wrong", true))
        assertEquals(AiFailure.BLOCKED, AiErrorClassifier.classify("PromptBlockedException", null, false))
        assertEquals(AiFailure.OTHER, AiErrorClassifier.classify("UnknownException", "weird", false))
    }

    @Test fun `AI setting persists and defaults on`() {
        val kv = MapKeyValueStore()
        assertTrue(PersistentSettingsStore(kv).settings.value.aiEnabled)
        PersistentSettingsStore(kv).update { it.copy(aiEnabled = false) }
        assertFalse(PersistentSettingsStore(kv).settings.value.aiEnabled)
    }
}
