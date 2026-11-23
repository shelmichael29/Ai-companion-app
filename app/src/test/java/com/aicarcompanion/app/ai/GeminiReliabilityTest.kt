@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.aicarcompanion.app.ai

import com.aicarcompanion.app.assistant.conversation.AssistantRequest
import com.aicarcompanion.app.assistant.conversation.CommandParser
import com.aicarcompanion.app.assistant.conversation.NoAdapterDiagnosticCommandHandler
import com.aicarcompanion.app.assistant.conversation.RuleBasedReasoningProvider
import com.aicarcompanion.app.assistant.conversation.VehicleContext
import com.aicarcompanion.app.connectivity.ConnectionLog
import com.aicarcompanion.app.connectivity.LogArea
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.diagnostics.dtc.GenericDtcCatalog
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Gemini reliability (Phase 7): accurate error handling, waiting, recovery and no duplicate requests. */
class GeminiReliabilityTest {

    private class Client : AiChatClient {
        val calls = mutableListOf<List<AiTurn>>()
        var next: () -> AiResult = { AiResult.Reply("Here's what I know.", "gemini-3.8-flash") }
        var resets = 0
        var inFlight = 0
        var maxInFlight = 0
        var gate: CompletableDeferred<Unit>? = null
        override val isConfigured = true
        override val modelName = "gemini-3.8-flash"
        override suspend fun generate(systemInstruction: String, history: List<AiTurn>, message: String): AiResult {
            calls += history
            inFlight++; maxInFlight = maxOf(maxInFlight, inFlight)
            try { gate?.await(); return next() } finally { inFlight-- }
        }
        override fun reset() { resets++ }
    }

    private var clock = Instant.parse("2026-11-17T12:00:00Z")
    private var online = true
    private val slept = mutableListOf<Long>()
    private val log = ConnectionLog(now = { clock })
    private val context = VehicleContext(null, null, emptyList(), ObdConnectionState.Disconnected, emptyList())
    private val parser = CommandParser { "Penelope" }

    private fun provider(client: Client) = GeminiReasoningProvider(
        parser, RuleBasedReasoningProvider(parser, { context }, NoAdapterDiagnosticCommandHandler, GenericDtcCatalog),
        { context }, client, GenericDtcCatalog, { true }, now = { clock },
        internetAvailable = { online }, log = log, sleep = { slept += it },
    )

    private suspend fun GeminiReasoningProvider.ask(text: String) =
        respond(AssistantRequest(text, "Penelope", ExplanationLevel.EVERYDAY))

    @Test fun `a genuine daily limit is explained once and Gemini isn't asked again for an hour`() = runTest {
        val c = Client().apply { next = { AiResult.Failure(AiFailure.QUOTA, "429 PerDay", httpStatus = 429) } }
        val p = provider(c)
        assertTrue(p.ask("Is synthetic oil worth it?").text.startsWith(AiNotices.DAILY_LIMIT))
        repeat(5) {
            clock = clock.plusSeconds(600)
            // Short notice each time, never the full explanation again, never silent.
            assertTrue(p.ask("Question $it").text.startsWith(AiNotices.stillUnavailable(AiFailure.QUOTA)))
        }
        assertEquals("no endless retries within the hour", 1, c.calls.size)
        assertEquals(AiStatus.Unavailable(AiFailure.QUOTA), p.status.value)
        clock = clock.plusSeconds(600)
        c.next = { AiResult.Reply("Back.", "gemini-3.8-flash") }
        assertEquals("${AiNotices.RESTORED} Back.", p.ask("Still there?").text)
    }

    @Test fun `with no internet Gemini isn't contacted and the user hears the internet notice`() = runTest {
        val c = Client()
        val p = provider(c)
        online = false
        val reply = p.ask("What's a good tire pressure?")
        assertTrue(reply.text.startsWith(AiNotices.OFFLINE))
        assertFalse("never blamed on a usage limit", reply.text.contains("limit"))
        assertEquals(0, c.calls.size)
        assertEquals(AiStatus.Unavailable(AiFailure.OFFLINE), p.status.value)
        online = true
        assertEquals("${AiNotices.RESTORED} Here's what I know.", p.ask("What's a good tire pressure?").text)
        assertEquals(1, c.calls.size)
    }

    @Test fun `a short server-requested wait is honored once and the answer still arrives`() = runTest {
        var first = true
        val c = Client().apply {
            next = { if (first) { first = false; AiResult.Failure(AiFailure.BUSY, "429 retry in 2s", retryAfterSeconds = 2) } else AiResult.Reply("Done.", modelName) }
        }
        val reply = provider(c).ask("Explain engine braking")
        assertEquals("Done.", reply.text)
        assertEquals(listOf(2_000L), slept)
        assertEquals(2, c.calls.size)
    }

    @Test fun `a long server wait is not slept through and requests resume after it`() = runTest {
        val c = Client().apply { next = { AiResult.Failure(AiFailure.BUSY, "retry in 45s", retryAfterSeconds = 45) } }
        val p = provider(c)
        p.ask("Question one")
        assertTrue(slept.isEmpty())
        clock = clock.plusSeconds(44); p.ask("Question two")
        assertEquals(1, c.calls.size)
        clock = clock.plusSeconds(2); p.ask("Question three")
        assertEquals(2, c.calls.size)
    }

    @Test fun `an authentication problem names its cause, pauses requests, and Reset retries at once`() = runTest {
        val c = Client().apply { next = { AiResult.Failure(AiFailure.APP_CHECK, "403 PERMISSION_DENIED", httpStatus = 403) } }
        val p = provider(c)
        val reply = p.ask("What is a CV joint?")
        assertTrue(reply.text.contains("App Check"))
        assertFalse(reply.text.contains("limit"))
        clock = clock.plusSeconds(30); p.ask("Again?")
        assertEquals("paused for a minute, not hammered", 1, c.calls.size)
        clock = clock.plusSeconds(31); p.ask("And again?")
        assertEquals("retried by itself after a minute, so a newly registered token works quickly", 2, c.calls.size)
        p.resetContext() // Settings → Reset conversation
        c.next = { AiResult.Reply("Verified now.", "gemini-3.8-flash") }
        assertEquals("Verified now.", p.ask("And now?").text)
    }

    @Test fun `repeated unexplained failures reset the client without losing the conversation`() = runTest {
        val c = Client()
        val p = provider(c)
        p.ask("My car is a 2011 BMW 528i, tell me about it")
        c.next = { AiResult.Failure(AiFailure.OTHER, "weird") }
        p.ask("Question two"); p.ask("Question three")
        assertEquals(1, c.resets)
        c.next = { AiResult.Reply("Fine.", "gemini-3.8-flash") }
        p.ask("Question four")
        val sent = c.calls.last().map { it.text }
        assertTrue("earlier conversation is kept", sent.any { it.contains("2011 BMW 528i") })
        assertFalse(sent.any { AiNotices.startsWithNotice(it) })
    }

    @Test fun `two questions at once never produce overlapping Gemini requests`() = runTest {
        val c = Client().apply { gate = CompletableDeferred() }
        val p = provider(c)
        val a = async { p.ask("First general question") }
        val b = async { p.ask("Second general question") }
        advanceUntilIdle()
        c.gate!!.complete(Unit)
        a.await(); b.await()
        assertEquals(1, c.maxInFlight)
        assertEquals(2, c.calls.size)
    }

    @Test fun `the diagnostic log records categories and status codes but never what the user said`() = runTest {
        val c = Client().apply { next = { AiResult.Failure(AiFailure.BUSY, "429", retryAfterSeconds = 30, httpStatus = 429) } }
        val p = provider(c)
        p.ask("My secret question about my ex's car")
        val text = log.export()
        assertTrue(text.contains("category=BUSY"))
        assertTrue(text.contains("httpStatus=429"))
        assertTrue(text.contains("retryAfterSeconds=30"))
        assertFalse(text.contains("secret question"))
        assertEquals(LogArea.AI, log.latest(LogArea.AI)?.area)
    }

    @Test fun `provider retry hints and status codes are read from real error messages`() {
        assertEquals(24L, AiErrorClassifier.retryAfterSeconds("Quota exceeded. Please retry in 23.4s."))
        assertEquals(17L, AiErrorClassifier.retryAfterSeconds("{\"retryDelay\": \"17s\"}"))
        assertEquals(1L, AiErrorClassifier.retryAfterSeconds("retry in 300ms"))
        assertNull(AiErrorClassifier.retryAfterSeconds("Internal error"))
        assertEquals(429, AiErrorClassifier.httpStatus("HTTP 429 Too Many Requests"))
        assertEquals(503, AiErrorClassifier.httpStatus("503 UNAVAILABLE"))
        assertNull(AiErrorClassifier.httpStatus("socket closed"))
    }
}
