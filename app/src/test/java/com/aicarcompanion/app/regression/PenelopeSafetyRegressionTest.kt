package com.aicarcompanion.app.regression

import com.aicarcompanion.app.ai.AiChatClient
import com.aicarcompanion.app.ai.AiFailure
import com.aicarcompanion.app.ai.AiResult
import com.aicarcompanion.app.ai.AiStatus
import com.aicarcompanion.app.ai.AiTurn
import com.aicarcompanion.app.ai.GeminiReasoningProvider
import com.aicarcompanion.app.assistant.conversation.AssistantReply
import com.aicarcompanion.app.assistant.conversation.AssistantRequest
import com.aicarcompanion.app.assistant.conversation.CommandParser
import com.aicarcompanion.app.assistant.conversation.NoAdapterDiagnosticCommandHandler
import com.aicarcompanion.app.assistant.conversation.ReplyBasis
import com.aicarcompanion.app.assistant.conversation.RuleBasedReasoningProvider
import com.aicarcompanion.app.assistant.conversation.VehicleContext
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.diagnostics.dtc.GenericDtcCatalog
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.smart.SymptomCategory
import com.aicarcompanion.app.diagnostics.smart.SymptomClassifier
import com.aicarcompanion.app.diagnostics.smart.SymptomGuide
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.Vehicle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Regression tests for Penelope's safety-critical answers and the Gemini fallback path.
 *
 * Gemini is replaced by [MockGemini], so no network, Firebase project or API key is used.
 * The mock deliberately returns *unsafe* advice ("keep driving") so a routing regression
 * that let the model answer a safety question would be caught.
 */
class PenelopeSafetyRegressionTest {

    /** Scripted stand-in for Gemini. Records every call. */
    private class MockGemini(var configured: Boolean = true) : AiChatClient {
        val calls = mutableListOf<String>()
        var respond: (String) -> AiResult = { AiResult.Reply(UNSAFE_ADVICE, "gemini-3.8-flash") }
        override val isConfigured get() = configured
        override val modelName = "gemini-3.8-flash"
        override suspend fun generate(systemInstruction: String, history: List<AiTurn>, message: String): AiResult {
            calls += message
            return respond(message)
        }
    }

    private val car = Vehicle(
        id = "bmw", year = 2011, make = "BMW", model = "528i", trim = "F10",
        engine = EngineSpec(3.0, 6, EngineLayout.INLINE, engineCode = "N52B30", fuelGrade = "Premium"),
        odometerMiles = 98_000,
    )
    private val context = VehicleContext(car, null, emptyList(), ObdConnectionState.Disconnected, emptyList())
    private val parser = CommandParser { "Penelope" }

    private fun rules() = RuleBasedReasoningProvider(parser, { context }, NoAdapterDiagnosticCommandHandler, GenericDtcCatalog)

    private fun penelope(gemini: MockGemini, aiEnabled: Boolean = true) = GeminiReasoningProvider(
        parser, rules(), { context }, gemini, GenericDtcCatalog, { aiEnabled },
        now = { Instant.parse("2026-11-16T12:00:00Z") },
    )

    private fun request(text: String, level: ExplanationLevel = ExplanationLevel.EVERYDAY) = AssistantRequest(text, "Penelope", level)

    /** The answer the app's own rules give, with no AI involved. */
    private suspend fun deterministicAnswer(text: String, level: ExplanationLevel = ExplanationLevel.EVERYDAY): AssistantReply =
        rules().respond(request(text, level))

    // ---- Safety-critical answers ----------------------------------------------------------------

    @Test fun `every urgent symptom phrase is recognized as its safety category`() {
        URGENT_PHRASES.forEach { (phrase, category) ->
            assertEquals(phrase, category, SymptomClassifier.classify(phrase))
            assertTrue("$category must be urgent", category.isUrgent)
        }
        val covered = URGENT_PHRASES.values.toSet()
        val urgent = SymptomCategory.entries.filter { it.isUrgent }.toSet()
        assertEquals("every urgent category needs a regression phrase", urgent, covered)
    }

    @Test fun `every urgent category has built-in safety advice`() {
        SymptomCategory.entries.filter { it.isUrgent }.forEach {
            assertNotNull("$it has no safety advice", SymptomGuide.safetyAdvice(it))
        }
    }

    @Test fun `urgent symptoms are answered by built-in safety logic, never Gemini`() = runTest {
        for (level in ExplanationLevel.entries) {
            URGENT_PHRASES.forEach { (phrase, category) ->
                val gemini = MockGemini()
                val reply = penelope(gemini).respond(request(phrase, level))
                assertTrue("Gemini was asked about \"$phrase\"", gemini.calls.isEmpty())
                assertTrue("\"$phrase\" ($level) lacks safety advice: ${reply.text}", reply.text.contains(SymptomGuide.safetyAdvice(category)!!))
                assertFalse(reply.text.contains(UNSAFE_ADVICE))
                assertNotEquals(ReplyBasis.AI_EXPLANATION, reply.basis)
                assertEquals(deterministicAnswer(phrase, level).text, reply.text)
            }
        }
    }

    @Test fun `safety answers are unchanged while Gemini is failing, off or not configured`() = runTest {
        val setups = listOf(
            "failing" to (MockGemini().apply { respond = { AiResult.Failure(AiFailure.OFFLINE) } } to true),
            "turned off" to (MockGemini() to false),
            "not configured" to (MockGemini(configured = false) to true),
        )
        for ((label, setup) in setups) {
            val (gemini, enabled) = setup
            val p = penelope(gemini, enabled)
            URGENT_PHRASES.forEach { (phrase, category) ->
                val reply = p.respond(request(phrase))
                assertTrue("[$label] \"$phrase\"", reply.text.contains(SymptomGuide.safetyAdvice(category)!!))
                assertFalse("[$label] no outage note on safety answers", reply.text.startsWith("I can't reach"))
            }
            assertTrue("[$label] Gemini must not be called", gemini.calls.isEmpty())
        }
    }

    @Test fun `can I still drive is always answered by the app`() = runTest {
        // More variations ("can I keep driving", "should I pull over"...) are covered in SafetyQuestionAndAiGuardTest.
        for (phrase in listOf("Can I still drive my car?", "is it safe to drive", "is it okay to drive", "should I drive it", "can I keep driving")) {
            val gemini = MockGemini()
            val reply = penelope(gemini).respond(request(phrase))
            assertTrue("Gemini was asked \"$phrase\"", gemini.calls.isEmpty())
            assertEquals(deterministicAnswer(phrase).text, reply.text)
        }
    }

    @Test fun `a safety follow-up after an AI conversation still gets safety logic`() = runTest {
        val gemini = MockGemini().apply { respond = { AiResult.Reply("Happy to chat about your BMW.", "gemini-3.8-flash") } }
        val p = penelope(gemini)
        assertEquals(ReplyBasis.AI_EXPLANATION, p.respond(request("Tell me about my car")).basis)
        val reply = p.respond(request("Now I see smoke coming from the hood"))
        assertEquals(1, gemini.calls.size)
        assertTrue(reply.text.contains(SymptomGuide.safetyAdvice(SymptomCategory.SMOKE_OR_BURNING)!!))
    }

    // ---- Gemini fallback ---------------------------------------------------------------------------

    @Test fun `every Gemini failure falls back to the built-in answer with a one-time notice`() = runTest {
        val question = "Is a BMW 528i expensive to maintain?"
        val basic = deterministicAnswer(question)
        for (reason in AiFailure.entries) {
            val gemini = MockGemini().apply { respond = { AiResult.Failure(reason, "detail") } }
            val p = penelope(gemini)
            val first = p.respond(request(question))
            assertEquals("[$reason] Gemini should have been tried", 1, gemini.calls.size)
            assertTrue("[$reason] notice missing: ${first.text}", first.text.startsWith("I can't reach my smarter answers right now"))
            assertTrue("[$reason] basic answer missing", first.text.endsWith(basic.text))
            assertTrue("[$reason] spoken text should match", first.spokenText.endsWith(basic.spokenText))
            assertNotEquals(ReplyBasis.AI_EXPLANATION, first.basis)
            val expectedStatus = if (reason == AiFailure.NOT_CONFIGURED) AiStatus.NotConfigured else AiStatus.Unavailable(reason)
            assertEquals(expectedStatus, p.status.value)

            val second = p.respond(request(question))
            assertEquals("[$reason] the notice is said only once", basic.text, second.text)
        }
    }

    @Test fun `Penelope recovers when Gemini comes back`() = runTest {
        var online = false
        val gemini = MockGemini().apply {
            respond = { if (online) AiResult.Reply("Back online.", "gemini-3.8-flash") else AiResult.Failure(AiFailure.TIMEOUT) }
        }
        val p = penelope(gemini)
        p.respond(request("How often should I change my oil?"))
        assertEquals(AiStatus.Unavailable(AiFailure.TIMEOUT), p.status.value)
        online = true
        val reply = p.respond(request("And what about brake fluid?"))
        assertEquals(ReplyBasis.AI_EXPLANATION, reply.basis)
        assertEquals("Back online.", reply.text)
        assertEquals(AiStatus.Ready("gemini-3.8-flash"), p.status.value)
    }

    @Test fun `AI replies that invent codes or readings are replaced with the verified answer`() = runTest {
        val question = "What could make my engine run rough?"
        val basic = deterministicAnswer(question)
        for (unsafe in listOf(
            "That's code P0420, your catalytic converter has failed.",
            "Your coolant temperature is currently 245 degrees, so stop now.",
        )) {
            val gemini = MockGemini().apply { respond = { AiResult.Reply(unsafe, "gemini-3.8-flash") } }
            val reply = penelope(gemini).respond(request(question))
            assertEquals(1, gemini.calls.size)
            assertEquals("unverified AI text must be replaced", basic.text, reply.text)
            assertNotEquals(ReplyBasis.AI_EXPLANATION, reply.basis)
        }
    }

    @Test fun `with smarter answers off or unconfigured Gemini is never called`() = runTest {
        for ((gemini, enabled, status) in listOf(
            Triple(MockGemini(), false, AiStatus.Off),
            Triple(MockGemini(configured = false), true, AiStatus.NotConfigured),
        )) {
            val p = penelope(gemini, enabled)
            val reply = p.respond(request("Tell me something fun about my car"))
            assertTrue(gemini.calls.isEmpty())
            assertTrue(reply.text.isNotBlank())
            assertEquals(status, p.status.value)
        }
    }

    private companion object {
        const val UNSAFE_ADVICE = "It's fine, just keep driving."

        /** One everyday phrase per urgent category (asserted complete above). */
        val URGENT_PHRASES = linkedMapOf(
            "I see smoke coming from the hood" to SymptomCategory.SMOKE_OR_BURNING,
            "I smell gas inside the car" to SymptomCategory.FUEL_SMELL,
            "my check engine light is flashing" to SymptomCategory.FLASHING_CHECK_ENGINE,
            "the oil pressure light came on" to SymptomCategory.OIL_PRESSURE,
            "my car is overheating" to SymptomCategory.OVERHEATING,
            "my brakes feel soft" to SymptomCategory.BRAKES,
        )
    }
}
