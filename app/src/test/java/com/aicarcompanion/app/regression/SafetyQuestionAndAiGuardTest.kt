package com.aicarcompanion.app.regression

import com.aicarcompanion.app.ai.AiChatClient
import com.aicarcompanion.app.ai.AiFailure
import com.aicarcompanion.app.ai.AiResult
import com.aicarcompanion.app.ai.AiStatus
import com.aicarcompanion.app.ai.AiTurn
import com.aicarcompanion.app.ai.GeminiReasoningProvider
import com.aicarcompanion.app.assistant.conversation.AssistantIntent
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
import com.aicarcompanion.app.diagnostics.smart.SymptomGuide
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.Vehicle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import java.time.Instant

/**
 * Regression tests for:
 * 1. Recognizing everyday "is it safe to keep driving?" questions so they always get Penelope's
 *    built-in, offline safety answer immediately (never Gemini), at every knowledge level.
 * 2. The defensive guard around the AI client: exceptions, errors and hangs fall back to the
 *    built-in answer instead of crashing or leaving Penelope silent.
 * Gemini is always a mock; nothing touches the network.
 */
class SafetyQuestionAndAiGuardTest {

    private class MockGemini(var configured: Boolean = true) : AiChatClient {
        val calls = mutableListOf<String>()
        var behavior: suspend (String) -> AiResult = { AiResult.Reply("It's fine, just keep driving.", "gemini-3.8-flash") }
        override val isConfigured get() = configured
        override val modelName = "gemini-3.8-flash"
        override suspend fun generate(systemInstruction: String, history: List<AiTurn>, message: String): AiResult {
            calls += message
            return behavior(message)
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

    private suspend fun builtIn(text: String, level: ExplanationLevel = ExplanationLevel.EVERYDAY): AssistantReply =
        rules().respond(request(text, level))

    // ---- 1. Safety-question recognition -----------------------------------------------------------

    @Test fun `everyday safety questions are recognized`() {
        SAFETY_QUESTIONS.forEach { assertEquals("\"$it\"", AssistantIntent.CanIDrive, parser.parse(it)) }
    }

    @Test fun `the original phrasings still work`() {
        listOf("Can I still drive my car?", "is it safe to drive", "is it okay to drive", "should I drive it", "can I drive")
            .forEach { assertEquals("\"$it\"", AssistantIntent.CanIDrive, parser.parse(it)) }
    }

    @Test fun `a safety question wins over help, repair quotes and other topics in the same sentence`() {
        listOf(
            "Help, should I pull over?",
            "The shop said I need brakes, is it safe to drive until then?",
            "my battery light is on, can I keep driving?",
            "Tell me more, is it safe to keep driving?",
        ).forEach { assertEquals("\"$it\"", AssistantIntent.CanIDrive, parser.parse(it)) }
    }

    @Test fun `urgent symptoms still get their specific safety advice even when asked as a driving question`() {
        val i = parser.parse("I see smoke, can I keep driving?")
        assertTrue(i is AssistantIntent.Symptom && i.category == SymptomCategory.SMOKE_OR_BURNING)
        val j = parser.parse("My car is overheating, should I pull over?")
        assertTrue(j is AssistantIntent.Symptom && j.category == SymptomCategory.OVERHEATING)
    }

    @Test fun `ordinary questions are not mistaken for safety questions`() {
        listOf(
            "Tell me more", "How do I change a tire?", "What's my battery voltage?", "Is a BMW 528i expensive to maintain?",
            "my car keeps stalling", "When is my next oil change?", "keep going",
        ).forEach { assertNotEquals("\"$it\"", AssistantIntent.CanIDrive, parser.parse(it)) }
    }

    @Test fun `safety questions get the built-in safety answer at every knowledge level, never Gemini`() = runTest {
        for (level in ExplanationLevel.entries) {
            for (q in SAFETY_QUESTIONS) {
                val gemini = MockGemini()
                val reply = penelope(gemini).respond(request(q, level))
                assertTrue("[$level] Gemini was asked \"$q\"", gemini.calls.isEmpty())
                assertTrue("[$level] \"$q\" lacks the safety guidance: ${reply.text}", reply.text.contains(PULL_OVER_GUIDANCE))
                assertFalse(reply.text.contains("just keep driving"))
                assertNotEquals(ReplyBasis.AI_EXPLANATION, reply.basis)
                assertEquals(builtIn(q, level).text, reply.text)
            }
        }
    }

    @Test fun `safety answers are immediate and work offline, with AI off, or unconfigured`() = runTest {
        val setups = listOf(
            "hanging" to (MockGemini().apply { behavior = { awaitCancellation() } } to true),
            "offline" to (MockGemini().apply { behavior = { AiResult.Failure(AiFailure.OFFLINE) } } to true),
            "throwing" to (MockGemini().apply { behavior = { throw IllegalStateException("boom") } } to true),
            "turned off" to (MockGemini() to false),
            "not configured" to (MockGemini(configured = false) to true),
        )
        for (level in ExplanationLevel.entries) {
            for ((label, setup) in setups) {
                val (gemini, enabled) = setup
                val p = penelope(gemini, enabled)
                for (q in listOf("Can I keep driving?", "Is it safe to drive?", "Should I pull over?")) {
                    val start = currentTime
                    val reply = p.respond(request(q, level))
                    assertEquals("[$label/$level] \"$q\" must not wait for the AI", start, currentTime)
                    assertTrue("[$label/$level] \"$q\"", reply.text.contains(PULL_OVER_GUIDANCE))
                    assertFalse("[$label/$level] no outage note on safety answers", reply.text.startsWith("I can't reach"))
                }
                assertTrue("[$label] Gemini must not be called", gemini.calls.isEmpty())
            }
        }
    }

    @Test fun `urgent symptom with a driving question gives the symptom's safety advice at every level`() = runTest {
        for (level in ExplanationLevel.entries) {
            val gemini = MockGemini()
            val reply = penelope(gemini).respond(request("The oil pressure light came on, can I keep driving?", level))
            assertTrue(gemini.calls.isEmpty())
            assertTrue("[$level]", reply.text.contains(SymptomGuide.safetyAdvice(SymptomCategory.OIL_PRESSURE)!!))
        }
    }

    // ---- 2. Defensive AI guard ----------------------------------------------------------------------

    private suspend fun assertFallsBack(p: GeminiReasoningProvider, question: String, level: ExplanationLevel, expected: AiFailure) {
        val reply = p.respond(request(question, level))
        assertTrue("[$expected/$level] notice missing: ${reply.text}", reply.text.startsWith("I can't reach my smarter answers right now"))
        assertTrue("[$expected/$level] built-in answer missing", reply.text.endsWith(builtIn(question, level).text))
        assertNotEquals(ReplyBasis.AI_EXPLANATION, reply.basis)
        assertEquals(AiStatus.Unavailable(expected), p.status.value)
    }

    @Test fun `unexpected exceptions from the AI client fall back instead of crashing, at every level`() = runTest {
        val question = "Is a BMW 528i expensive to maintain?"
        val cases = listOf<Pair<Throwable, AiFailure>>(
            IllegalStateException("unexpected SDK state") to AiFailure.OTHER,
            RuntimeException("wrapped", UnknownHostException("firebasevertexai.googleapis.com")) to AiFailure.OFFLINE,
            IOException("socket closed") to AiFailure.OFFLINE,
            // A 429 without a daily-quota marker is a temporary rate limit (TemporaryAiErrorsRegressionTest).
            RuntimeException("429 RESOURCE_EXHAUSTED: quota") to AiFailure.BUSY,
            RuntimeException("429 RESOURCE_EXHAUSTED: quotaId GenerateRequestsPerDayPerProjectPerModel") to AiFailure.QUOTA,
            NullPointerException() to AiFailure.OTHER,
            NoClassDefFoundError("com/google/firebase/ai/GenerativeModel") to AiFailure.OTHER,
        )
        for (level in ExplanationLevel.entries) {
            for ((error, expected) in cases) {
                val gemini = MockGemini().apply { behavior = { throw error } }
                assertFallsBack(penelope(gemini), question, level, expected)
                assertEquals(1, gemini.calls.size)
            }
        }
    }

    @Test fun `a hung AI client times out and Penelope answers anyway, at every level`() = runTest {
        for (level in ExplanationLevel.entries) {
            val gemini = MockGemini().apply { behavior = { awaitCancellation() } }
            val p = penelope(gemini)
            val start = currentTime
            assertFallsBack(p, "What does a timing chain do?", level, AiFailure.TIMEOUT)
            assertEquals("gives up after the 30 s limit", 30_000L, currentTime - start)
        }
    }

    @Test fun `a timeout thrown inside the client is treated as a timeout, not a crash`() = runTest {
        val gemini = MockGemini().apply { behavior = { withTimeout(10) { delay(1_000) }; error("unreachable") } }
        assertFallsBack(penelope(gemini), "How often should I rotate my tires?", ExplanationLevel.DIY, AiFailure.TIMEOUT)
    }

    @Test fun `Penelope keeps working normally after an AI exception`() = runTest {
        var fail = true
        val gemini = MockGemini().apply {
            behavior = { if (fail) error("boom") else AiResult.Reply("Glad to help.", "gemini-3.8-flash") }
        }
        val p = penelope(gemini)
        assertFallsBack(p, "Tell me about my car", ExplanationLevel.ADVANCED, AiFailure.OTHER)
        fail = false
        val reply = p.respond(request("And what about its engine?", ExplanationLevel.ADVANCED))
        assertEquals(ReplyBasis.AI_EXPLANATION, reply.basis)
        assertEquals(AiStatus.Ready("gemini-3.8-flash"), p.status.value)
    }

    @Test fun `ending the conversation still cancels a pending AI request`() = runTest {
        val gemini = MockGemini().apply { behavior = { awaitCancellation() } }
        val p = penelope(gemini)
        val pending = async { p.respond(request("Tell me a fun fact about BMW")) }
        settle(1_000)
        pending.cancel()
        settle()
        assertTrue("cancellation must propagate, not become a fallback answer", pending.isCancelled)
    }

    private fun TestScope.settle(millis: Long = 0) {
        if (millis > 0) advanceTimeBy(millis)
        runCurrent()
    }

    private companion object {
        /** Part of the built-in "can I still drive?" answer that tells the driver when to stop. */
        const val PULL_OVER_GUIDANCE = "pull over safely and get help"

        val SAFETY_QUESTIONS = listOf(
            "Can I keep driving?", "Is it safe to drive?", "Should I pull over?",
            "Is it okay to keep driving?", "Can I continue driving?", "Should I stop driving?", "Do I need to stop?",
            "Can I drive it home?", "Is it dangerous to drive?", "Is my car safe to drive?", "Should I be driving it?",
            "Is it alright to drive like this?", "Hey Penelope, can I keep driving?",
        )
    }
}
