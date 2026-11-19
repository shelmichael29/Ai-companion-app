package com.aicarcompanion.app.ai

import com.aicarcompanion.app.assistant.VoiceSession
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.assistant.conversation.AssistantRequest
import com.aicarcompanion.app.assistant.conversation.CodeReadResult
import com.aicarcompanion.app.assistant.conversation.CommandParser
import com.aicarcompanion.app.assistant.conversation.ConversationManager
import com.aicarcompanion.app.assistant.conversation.DiagnosticCommandHandler
import com.aicarcompanion.app.assistant.conversation.LiveValueResult
import com.aicarcompanion.app.assistant.conversation.ReplyBasis
import com.aicarcompanion.app.assistant.conversation.RuleBasedReasoningProvider
import com.aicarcompanion.app.assistant.conversation.VehicleContext
import com.aicarcompanion.app.assistant.voice.NoAudioFocus
import com.aicarcompanion.app.assistant.voice.SpeakResult
import com.aicarcompanion.app.assistant.voice.SpeechInput
import com.aicarcompanion.app.assistant.voice.SpeechInputEvent
import com.aicarcompanion.app.assistant.voice.SpeechOutput
import com.aicarcompanion.app.assistant.voice.SpeechOutputState
import com.aicarcompanion.app.assistant.voice.VoiceConfig
import com.aicarcompanion.app.connectivity.ConnectionLog
import com.aicarcompanion.app.diagnostics.dtc.GenericDtcCatalog
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import com.aicarcompanion.app.diagnostics.obd.ObdTransport
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DtcStatus
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.RecordedTroubleCode
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.domain.model.TroubleCodeRead
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.handsfree.HandsFreeReasoningProvider
import com.aicarcompanion.app.handsfree.android.AndroidHandsFreeCapabilities
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Gemini as Penelope's primary intelligence: what reaches Gemini, what stays deterministic,
 * real data first, honest fallbacks, timeouts and response-source diagnostics. Fakes only.
 */
class GeminiPrimaryRoutingTest {

    private class FakeGemini : AiChatClient {
        data class Call(val system: String, val history: List<AiTurn>, val message: String)
        val calls = mutableListOf<Call>()
        var inFlight = 0
        var maxInFlight = 0
        var next: suspend (String) -> AiResult = { AiResult.Reply("Here's what I think about that.", MODEL) }
        override val isConfigured = true
        override val modelName = MODEL
        override suspend fun generate(systemInstruction: String, history: List<AiTurn>, message: String): AiResult {
            calls += Call(systemInstruction, history, message)
            inFlight++; maxInFlight = maxOf(maxInFlight, inFlight)
            try { return next(message) } finally { inFlight-- }
        }
    }

    /** A connected adapter that answers with real-looking readings and records what was read. */
    private class FakeCar : DiagnosticCommandHandler {
        val reads = mutableListOf<String>()
        override suspend fun readLiveValue(pid: ObdPid): LiveValueResult {
            reads += pid.name
            return LiveValueResult.Value(SensorReading("Coolant", 90.0, "°C", Instant.EPOCH, DataOrigin.OBD_BLUETOOTH, pid.command))
        }
        override suspend fun readTroubleCodes(vehicleId: String): CodeReadResult {
            reads += "codes"
            return CodeReadResult.Read(
                TroubleCodeRead("r", vehicleId, Instant.EPOCH, DataOrigin.OBD_BLUETOOTH,
                    listOf(RecordedTroubleCode(1, vehicleId, "P0171", DtcStatus.STORED, Instant.EPOCH, "r", DataOrigin.OBD_BLUETOOTH))),
            )
        }
    }

    private val bmw = Vehicle(
        id = "bmw", year = 2011, make = "BMW", model = "528i", trim = "F10",
        engine = EngineSpec(3.0, 6, EngineLayout.INLINE, engineCode = "N52B30"), odometerMiles = 98_000,
    )
    private val connected = ObdConnectionState.Connected(ObdTransport.BLUETOOTH_CLASSIC, "OBDII", vehicleProtocol = "ISO 15765-4 CAN")
    private var online = true
    private val log = ConnectionLog(now = { Instant.EPOCH })

    private fun TestScope.penelope(gemini: FakeGemini, car: FakeCar = FakeCar(), timeout: Long = AiTimeouts.DEFAULT_MILLIS): GeminiReasoningProvider {
        val parser = CommandParser { "Penelope" }
        val ctx = VehicleContext(bmw, null, emptyList(), connected, emptyList(), emptyList())
        val rules = RuleBasedReasoningProvider(parser, { ctx }, car, GenericDtcCatalog)
        return GeminiReasoningProvider(
            parser, rules, { ctx }, gemini, GenericDtcCatalog, aiEnabled = { true },
            now = { Instant.parse("2026-11-19T12:00:00Z").plusMillis(currentTime) },
            aiTimeoutMillis = { timeout }, internetAvailable = { online }, log = log,
            sleep = { delay(it) }, clockMillis = { currentTime },
        )
    }

    private suspend fun GeminiReasoningProvider.ask(text: String) = respond(AssistantRequest(text, "Penelope"))

    // 1. Normal conversations reach Gemini --------------------------------------------------------------

    @Test fun `normal conversations reach Gemini and are recorded as Gemini answers`() = runTest {
        val gemini = FakeGemini()
        val p = penelope(gemini)
        for (q in listOf("Hi Penelope", "Is a BMW 528i expensive to maintain?", "Can you help me?", "What does an oxygen sensor do?",
            "Tell me more", "Thanks", "What car am I driving?", "My brakes squeak a little in the morning")) {
            val before = gemini.calls.size
            val r = p.ask(q)
            assertEquals("'$q' should reach Gemini", before + 1, gemini.calls.size)
            assertFalse("'$q' must not carry a fallback notice", AiNotices.startsWithNotice(r.text))
        }
        val last = p.sources.last.value!!
        assertTrue(last.source.isGemini)
        assertEquals(MODEL, last.model)
    }

    // 2 and 3. Check-engine-light questions: real data first, then Gemini explains -----------------------

    @Test fun `a check engine light question reads the codes first, then Gemini explains them`() = runTest {
        val gemini = FakeGemini()
        val car = FakeCar()
        val p = penelope(gemini, car)
        gemini.next = {
            assertEquals("codes are read before Gemini is asked", listOf("codes"), car.reads)
            AiResult.Reply("Your car stored P0171, which means it's running lean. A vacuum leak is a common cause.", MODEL)
        }
        val r = p.ask("My check engine light is on, what does it mean?")
        val sent = gemini.calls.single().system
        assertTrue(sent.contains("APP RESULT FOR THIS QUESTION"))
        assertTrue(sent.contains("P0171"))
        assertTrue(r.text.contains("P0171"))
        assertEquals(ResponseSource.GEMINI_WITH_VEHICLE_DATA, p.sources.last.value!!.source)
    }

    @Test fun `a live reading is read from the car before Gemini explains it, and Gemini can't change it`() = runTest {
        val gemini = FakeGemini()
        val car = FakeCar()
        val p = penelope(gemini, car)
        gemini.next = { AiResult.Reply("Your coolant is reading 194 degrees Fahrenheit right now.", MODEL) }
        val ok = p.ask("What's my coolant temperature right now?")
        assertEquals(listOf("COOLANT_TEMP"), car.reads)
        assertTrue(gemini.calls.single().system.contains("194 degrees Fahrenheit"))
        assertEquals("Your coolant is reading 194 degrees Fahrenheit right now.", ok.text)
        assertEquals("keeps the data label", ReplyBasis.LIVE_VEHICLE_DATA, ok.basis)

        // A different number than the app read is rejected: the user hears the real reading and why.
        gemini.next = { AiResult.Reply("Your coolant is reading 250 degrees right now.", MODEL) }
        val bad = p.ask("What's my coolant temperature right now?")
        assertTrue(bad.text.startsWith(AiNotices.UNVERIFIED))
        assertTrue(bad.text.contains("194 degrees Fahrenheit"))
        assertFalse(bad.text.contains("250"))
        assertEquals(ResponseSource.INVALID_RESPONSE, p.sources.last.value!!.source)
    }

    // 4. Emergencies stay deterministic -----------------------------------------------------------------

    @Test fun `emergency symptoms and conversation controls never wait for Gemini`() = runTest {
        val gemini = FakeGemini()
        val p = penelope(gemini)
        for (q in listOf("I smell gas", "There's smoke coming from the hood", "My brakes stopped working", "Can I still drive my car?")) {
            p.ask(q)
            assertEquals(ResponseSource.SAFETY, p.sources.last.value!!.source)
        }
        p.ask("goodbye")
        assertEquals(ResponseSource.CONVERSATION_CONTROL, p.sources.last.value!!.source)
        assertEquals(0, gemini.calls.size)
    }

    /** Regression: "my brakes stopped working" and "my brakes failed" weren't recognized as emergencies. */
    @Test fun `brake failure in everyday words is an emergency`() {
        val parser = CommandParser { "Penelope" }
        for (q in listOf("My brakes stopped working", "my brakes failed", "the brakes quit working", "I have no brakes", "I lost my brakes", "my brakes gave out")) {
            val intent = parser.parse(q)
            assertTrue("'$q' -> $intent", intent is com.aicarcompanion.app.assistant.conversation.AssistantIntent.Symptom && intent.category.isUrgent)
            assertEquals(AiRouting.Route.DETERMINISTIC, AiRouting.route(intent, parser.normalize(q)))
        }
    }

    // Review findings (each failed before its fix) ---------------------------------------------------------

    @Test fun `a safety question that mentions a code still gets the built-in safety answer`() = runTest {
        val gemini = FakeGemini()
        val p = penelope(gemini)
        for (q in listOf("Can I drive with P0300?", "my brakes failed, I have P0300", "I smell burning and have code P0171")) {
            p.ask(q)
            assertEquals("'$q'", ResponseSource.SAFETY, p.sources.last.value!!.source)
        }
        assertEquals(0, gemini.calls.size)
    }

    @Test fun `the reply guard rejects readings that only look verified`() {
        val facts = "Your car has code P0300 stored. Your battery voltage is 14.2 volts, read from your car just now."
        val codes = setOf("P0300")
        assertNull("the real reading passes", ReplyGuard.check("Your battery is at 14.2 volts right now.", codes, facts))
        assertEquals("code digits aren't readings", ReplyGuard.Violation.UNVERIFIED_READING, ReplyGuard.check("Your coolant is reading 300 degrees.", codes, facts))
        assertEquals("same number, wrong unit", ReplyGuard.Violation.UNVERIFIED_READING, ReplyGuard.check("Your coolant is reading 14.2 degrees.", codes, facts))
        assertEquals("hedges don't excuse a live claim", ReplyGuard.Violation.UNVERIFIED_READING, ReplyGuard.check("Your coolant is reading about 130 degrees.", emptySet()))
        assertEquals("a bare value is a claim", ReplyGuard.Violation.UNVERIFIED_READING, ReplyGuard.check("Coolant is 130 degrees.", emptySet()))
        assertEquals("'normal' doesn't excuse a value for your car", ReplyGuard.Violation.UNVERIFIED_READING, ReplyGuard.check("Your coolant is at 130 degrees, which is normal.", emptySet()))
        assertEquals("lowercase codes are checked", ReplyGuard.Violation.INVENTED_CODE, ReplyGuard.check("You might also have p0301.", codes))
        assertNull("general knowledge still passes", ReplyGuard.check("A healthy battery is about 12.6 volts at rest.", emptySet()))
        assertNull(ReplyGuard.check("Most engines normally run between 195 and 220 degrees.", emptySet()))
        assertNull(ReplyGuard.check("Your engine should normally run around 200 degrees.", emptySet()))
    }

    @Test fun `the adapter's Bluetooth name never reaches Gemini`() = runTest {
        val gemini = FakeGemini()
        val p = penelope(gemini)
        p.ask("Is my adapter connected?")
        assertEquals(0, gemini.calls.size)
        assertEquals(ResponseSource.DEVICE_COMMAND, p.sources.last.value!!.source)
        p.ask("Tell me about my car")
        assertFalse(gemini.calls.single().system.contains("OBDII"))
    }

    @Test fun `a symptom is saved once, whether Gemini answers or not`() = runTest {
        val saved = mutableListOf<com.aicarcompanion.app.domain.model.DiagnosticObservation>()
        val parser = CommandParser { "Penelope" }
        val ctx = VehicleContext(bmw, null, emptyList(), connected, emptyList(), emptyList())
        val rules = RuleBasedReasoningProvider(parser, { ctx }, FakeCar(), GenericDtcCatalog, { saved += it })
        val gemini = FakeGemini()
        val p = GeminiReasoningProvider(parser, rules, { ctx }, gemini, GenericDtcCatalog, { true }, { saved += it })
        p.ask("My car shakes a little at stop lights")
        assertEquals(1, saved.size)
        gemini.next = { AiResult.Failure(AiFailure.OTHER) }
        p.ask("It also squeaks when I turn")
        assertEquals("the fallback saves it, the AI path doesn't too", 2, saved.size)
    }

    @Test fun `asking for codes with "anyway" still reads them`() {
        val parser = CommandParser { "Penelope" }
        val q = "What are my codes anyway?"
        assertEquals(AiRouting.Route.DATA_THEN_AI, AiRouting.route(parser.parse(q), parser.normalize(q)))
    }

    @Test fun `a built-in follow-up question doesn't linger after Gemini answers`() = runTest {
        val gemini = FakeGemini()
        val p = penelope(gemini)
        p.ask("Can I still drive my car?") // built-in safety answer may ask a follow-up
        p.ask("What's the best motor oil for winter?") // Gemini answers
        gemini.next = { AiResult.Failure(AiFailure.OTHER) }
        val fallback = p.ask("Blue")
        assertTrue(fallback.text.startsWith(AiNotices.TEMPORARY))
        assertFalse("not treated as an answer to the old safety question", fallback.text.contains("drive", ignoreCase = true) && fallback.text.contains("safe", ignoreCase = true))
    }

    // 5. No internet ------------------------------------------------------------------------------------

    @Test fun `with the internet off, Gemini isn't contacted and answers are honest and still useful`() = runTest {
        val gemini = FakeGemini()
        val p = penelope(gemini)
        online = false
        val general = p.ask("Is premium fuel necessary?")
        assertTrue(general.text.startsWith(AiNotices.OFFLINE))
        val data = p.ask("What's my coolant temperature right now?")
        assertTrue("short notice later, never silent", data.text.startsWith(AiNotices.stillUnavailable(AiFailure.OFFLINE)))
        assertTrue("the real reading is still given offline", data.text.contains("194 degrees Fahrenheit"))
        assertEquals(0, gemini.calls.size)
        assertEquals(ResponseSource.OFFLINE_FALLBACK, p.sources.last.value!!.source)
        assertTrue(p.sources.last.value!!.skipped)
    }

    // 6. Firebase and App Check failures are told apart -------------------------------------------------

    @Test fun `App Check, authentication, quota, rate limit and timeout are classified separately`() {
        fun c(type: String, msg: String) = AiErrorClassifier.classify(type, msg, causeIsNetwork = false)
        assertEquals(AiFailure.APP_CHECK, c("FirebaseAppCheckException", "Error returned from App Check"))
        assertEquals(AiFailure.APP_CHECK, c("ServerException", "403 PERMISSION_DENIED: Firebase App Check token is invalid."))
        assertEquals(AiFailure.APP_CHECK, c("ServerException", "401 UNAUTHENTICATED"))
        assertEquals(AiFailure.NOT_CONFIGURED, c("ServerException", "400 API key not valid. Please pass a valid API key."))
        assertEquals(AiFailure.QUOTA, c("QuotaExceededException", "429 quotaId GenerateRequestsPerDayPerProject"))
        assertEquals(AiFailure.BUSY, c("ServerException", "429 RESOURCE_EXHAUSTED retry in 20s"))
        assertEquals(AiFailure.TIMEOUT, c("RequestTimeoutException", "Request timed out"))

        assertEquals(ResponseSource.AUTH_ERROR, ResponseSource.forFailure(AiFailure.APP_CHECK))
        assertEquals(ResponseSource.AUTH_ERROR, ResponseSource.forFailure(AiFailure.NOT_CONFIGURED))
        assertEquals(ResponseSource.QUOTA, ResponseSource.forFailure(AiFailure.QUOTA))
        assertEquals(ResponseSource.RATE_LIMIT, ResponseSource.forFailure(AiFailure.BUSY))
        assertEquals(ResponseSource.AI_TIMEOUT, ResponseSource.forFailure(AiFailure.TIMEOUT))
        assertEquals(ResponseSource.INVALID_RESPONSE, ResponseSource.forFailure(AiFailure.EMPTY))
        assertEquals(ResponseSource.AI_SERVICE_ERROR, ResponseSource.forFailure(AiFailure.OTHER))
    }

    @Test fun `an App Check failure is recorded as an authentication error and retried after a minute`() = runTest {
        val gemini = FakeGemini().apply { next = { AiResult.Failure(AiFailure.APP_CHECK, "403", httpStatus = 403) } }
        val p = penelope(gemini)
        assertTrue(p.ask("What is a CV joint?").text.contains("App Check"))
        assertEquals(ResponseSource.AUTH_ERROR, p.sources.last.value!!.source)
        gemini.next = { AiResult.Reply("A CV joint lets the axle turn while it moves.", MODEL) }
        advanceTimeBy(61_000)
        assertEquals("${AiNotices.RESTORED} A CV joint lets the axle turn while it moves.", p.ask("What is a CV joint?").text)
    }

    // 7. Timeout and automatic recovery -----------------------------------------------------------------

    @Test fun `a slow Gemini times out at the configured limit, then recovers on the next question`() = runTest {
        val gemini = FakeGemini().apply { next = { awaitCancellation() } }
        val p = penelope(gemini)
        val start = currentTime
        val r = p.ask("Why do tires lose pressure in winter?")
        assertEquals(AiTimeouts.DEFAULT_MILLIS, currentTime - start)
        assertTrue(r.text.startsWith(AiNotices.TEMPORARY))
        val rec = p.sources.last.value!!
        assertEquals(ResponseSource.AI_TIMEOUT, rec.source)
        assertEquals(AiTimeouts.DEFAULT_MILLIS, rec.durationMillis)

        gemini.next = { AiResult.Reply("Cold air is denser, so pressure drops.", MODEL) }
        assertEquals("${AiNotices.RESTORED} Cold air is denser, so pressure drops.", p.ask("Why do tires lose pressure in winter?").text)
        assertEquals(ResponseSource.GEMINI, p.sources.last.value!!.source)
    }

    @Test fun `the timeout is configurable and clamped to a safe range`() = runTest {
        val gemini = FakeGemini().apply { next = { delay(15_000); AiResult.Reply("Worth the wait.", MODEL) } }
        val p = penelope(gemini, timeout = 20_000)
        assertEquals("a 15 s answer arrives with a 20 s limit", "Worth the wait.", p.ask("Tell me a car fact").text)
        assertEquals(12_000L, AiTimeouts.choose(null))
        assertEquals(6_000L, AiTimeouts.choose(1_000))
        assertEquals(30_000L, AiTimeouts.choose(120_000))
        assertEquals(15_000L, AiTimeouts.choose(15_000))
    }

    // 8. Multi-turn continuity --------------------------------------------------------------------------

    @Test fun `follow-ups keep the whole conversation, including data answers and a timed-out turn`() = runTest {
        val gemini = FakeGemini()
        val p = penelope(gemini)
        p.ask("My check engine light is on, what does it mean?")
        gemini.next = { awaitCancellation() }
        p.ask("Is it serious?")
        gemini.next = { AiResult.Reply("You can check for a vacuum leak first.", MODEL) }
        p.ask("What should I check first?")
        val history = gemini.calls.last().history
        assertEquals(4, history.size)
        assertEquals("My check engine light is on, what does it mean?", history[0].text)
        assertEquals("Is it serious?", history[2].text)
        assertFalse("notices never enter Gemini's history", history.any { AiNotices.startsWithNotice(it.text) })
    }

    // 9. No duplicated or overlapping responses ---------------------------------------------------------

    private class Input : SpeechInput {
        private var channel = Channel<SpeechInputEvent>(Channel.UNLIMITED)
        override val isAvailable = true
        override fun listen(): Flow<SpeechInputEvent> = flow { channel = Channel(Channel.UNLIMITED); emitAll(channel) }
        override fun finishListening() = Unit
        fun say(text: String) { channel.trySend(SpeechInputEvent.Final(text)); channel.close() }
    }

    private class Output : SpeechOutput {
        override val state = MutableStateFlow<SpeechOutputState>(SpeechOutputState.Ready)
        val spoken = mutableListOf<String>()
        var speaking = 0
        var overlaps = 0
        override suspend fun speak(text: String): SpeakResult {
            if (speaking > 0) overlaps++
            speaking++; spoken += text; delay(500); speaking--
            return SpeakResult.Completed
        }
        override fun stop() = Unit
        override fun configure(config: VoiceConfig) = Unit
    }

    @Test fun `a slow answer shows Still thinking, then exactly one reply is spoken and listening resumes`() = runTest {
        val gemini = FakeGemini().apply { next = { delay(6_000); AiResult.Reply("Rotating tires evens out wear.", MODEL) } }
        val input = Input(); val output = Output()
        val s = VoiceSession(this, input, output, ConversationManager(penelope(gemini), { "Penelope" }), NoAudioFocus(), { true })
        s.start(); advanceUntilIdle()
        input.say("Why rotate tires?")
        runCurrent()
        assertEquals(VoiceState.Processing("Why rotate tires?"), s.state.value)
        advanceTimeBy(4_100)
        assertEquals("a quiet on-screen notice, nothing spoken", VoiceState.Processing("Why rotate tires?", slow = true), s.state.value)
        assertTrue(output.spoken.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf("Rotating tires evens out wear."), output.spoken)
        assertTrue(s.state.value is VoiceState.Listening)
        assertEquals(0, output.overlaps)
        s.end(); advanceUntilIdle()
    }

    @Test fun `a timed-out request is never answered twice and requests never overlap`() = runTest {
        val gemini = FakeGemini().apply { next = { awaitCancellation() } }
        val input = Input(); val output = Output()
        val s = VoiceSession(this, input, output, ConversationManager(penelope(gemini), { "Penelope" }), NoAudioFocus(), { true })
        s.start(); advanceUntilIdle()
        input.say("Why rotate tires?"); advanceUntilIdle()
        assertEquals("one fallback reply only", 1, output.spoken.size)
        assertTrue(output.spoken.single().startsWith(AiNotices.TEMPORARY))
        advanceTimeBy(60_000); runCurrent()
        assertEquals("no late second answer", 1, output.spoken.size)
        assertEquals(1, gemini.maxInFlight)
        s.end(); advanceUntilIdle()
    }

    // 10. Privacy-safe response-source log -----------------------------------------------------------------

    @Test fun `every answer is logged with its source, duration and model, but never the words`() = runTest {
        val gemini = FakeGemini().apply { next = { AiResult.Reply("Secret reply about Aunt Mary's Honda.", MODEL) } }
        val p = penelope(gemini)
        p.ask("My sister Mary's car smells weird")
        p.ask("I smell gas")
        val handsFree = HandsFreeReasoningProvider(p, AndroidHandsFreeCapabilities()) { _, status ->
            p.sources.record(ResponseRecord(ResponseSource.DEVICE_COMMAND, category = status))
        }
        handsFree.respond(AssistantRequest("call Mary Johnson", "Penelope"))

        val answers = log.events.value.filter { it.event == "Answer" }
        assertEquals(listOf("GEMINI", "SAFETY", "DEVICE_COMMAND"), answers.map { it.details["source"] })
        assertEquals(MODEL, answers[0].details["model"])
        assertTrue(answers[0].details.containsKey("ms"))
        val text = log.export()
        listOf("Mary", "Honda", "smells", "sister", "gas", "Johnson", "Secret").forEach { assertFalse("'$it' leaked into the log", text.contains(it)) }
        assertNull(answers[2].details["model"])
    }

    @Test fun `the status screen describes the latest answer source plainly`() {
        assertEquals("No answers yet in this session.", ResponseSourceTracker.describe(null))
        assertEquals("Gemini ($MODEL, 1.8 s)", ResponseSourceTracker.describe(ResponseRecord(ResponseSource.GEMINI, 1_800, MODEL)))
        assertEquals("Built-in answer: Gemini timed out (12.0 s)", ResponseSourceTracker.describe(ResponseRecord(ResponseSource.AI_TIMEOUT, 12_000, MODEL)))
    }

    private companion object { const val MODEL = "gemini-3.8-flash" }
}
