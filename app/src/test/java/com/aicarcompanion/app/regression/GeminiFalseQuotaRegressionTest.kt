package com.aicarcompanion.app.regression

import com.aicarcompanion.app.ai.AiChatClient
import com.aicarcompanion.app.ai.AiFailure
import com.aicarcompanion.app.ai.AiResult
import com.aicarcompanion.app.ai.AiTurn
import com.aicarcompanion.app.ai.GeminiReasoningProvider
import com.aicarcompanion.app.assistant.conversation.AssistantRequest
import com.aicarcompanion.app.assistant.conversation.CommandParser
import com.aicarcompanion.app.assistant.conversation.NoAdapterDiagnosticCommandHandler
import com.aicarcompanion.app.assistant.conversation.RuleBasedReasoningProvider
import com.aicarcompanion.app.assistant.conversation.VehicleContext
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.diagnostics.dtc.GenericDtcCatalog
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Phase 7 regression: "After nearly every question Penelope says Gemini's daily usage is
 * exhausted; restarting the app temporarily fixes it."
 *
 * Two causes reproduced here, both failing before the fix:
 * 1. The outage notice ("I can't reach my smarter answers … usage limit …") was saved into the
 *    conversation history sent with every later request, so Gemini saw itself saying the limit was
 *    reached and could keep repeating it. A restart cleared the in-memory history.
 * 2. During a rate limit Penelope sent every new question straight back to Gemini, ignoring the
 *    server's "retry in N s", so each question failed again (and made the limiting worse).
 */
class GeminiFalseQuotaRegressionTest {

    private val perMinute = "429 RESOURCE_EXHAUSTED: Quota exceeded for quota metric 'Generate requests', " +
        "quotaId: GenerateRequestsPerMinutePerProjectPerModel-FreeTier. Please retry in 40s."

    private class ScriptedClient(val script: MutableList<() -> AiResult>) : AiChatClient {
        val histories = mutableListOf<List<AiTurn>>()
        override val isConfigured = true
        override val modelName = "gemini-3.8-flash"
        override suspend fun generate(systemInstruction: String, history: List<AiTurn>, message: String): AiResult {
            histories += history
            return script.removeFirstOrNull()?.invoke() ?: AiResult.Reply("Sure, happy to help.", modelName)
        }
    }

    private var clock = Instant.parse("2026-11-17T12:00:00Z")
    private val context = VehicleContext(null, null, emptyList(), ObdConnectionState.Disconnected, emptyList())
    private val parser = CommandParser { "Penelope" }

    private fun penelope(client: AiChatClient) = GeminiReasoningProvider(
        parser, RuleBasedReasoningProvider(parser, { context }, NoAdapterDiagnosticCommandHandler, GenericDtcCatalog),
        { context }, client, GenericDtcCatalog, { true }, now = { clock },
    )

    private fun ask(text: String) = AssistantRequest(text, "Penelope", ExplanationLevel.EVERYDAY)

    @Test fun `the outage notice is never sent back to Gemini as something Penelope said`() = runTest {
        val client = ScriptedClient(mutableListOf({ AiResult.Failure(AiFailure.QUOTA, "429 per day") }))
        val p = penelope(client)
        val first = p.respond(ask("How often should I change my oil?"))
        assertTrue("the user is told about the daily limit", first.text.startsWith(com.aicarcompanion.app.ai.AiNotices.DAILY_LIMIT))

        clock = clock.plusSeconds(3 * 3600) // well after any limit
        p.respond(ask("And my brake fluid?"))
        val sent = client.histories.last().joinToString("\n") { it.text }.lowercase()
        assertFalse("history must not contain the outage notice:\n$sent", sent.contains("can't reach"))
        assertFalse(sent.contains("limit"))
        assertFalse(sent.contains("trouble reaching"))
    }

    @Test fun `during a rate limit Penelope waits for the server's retry time instead of asking again`() = runTest {
        val client = ScriptedClient(mutableListOf({ AiResult.Failure(AiFailure.BUSY, perMinute, retryAfterSeconds = 40) }))
        val p = penelope(client)
        p.respond(ask("Tell me about my car's maintenance"))
        assertEquals(1, client.histories.size)

        // Three more questions inside the 40 s window: none of them hits Gemini.
        repeat(3) { i ->
            clock = clock.plusSeconds(5)
            val reply = p.respond(ask("Another general question number $i"))
            assertFalse("no repeated outage announcement: ${reply.text}", reply.text.contains("trouble reaching"))
        }
        assertEquals("no requests during the retry window", 1, client.histories.size)

        // After the window, the next question works without restarting the app.
        clock = clock.plusSeconds(40)
        val back = p.respond(ask("What about tire rotation?"))
        assertEquals(2, client.histories.size)
        assertEquals("Connection restored. We can continue. Sure, happy to help.", back.text)
    }
}
