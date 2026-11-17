package com.aicarcompanion.app.regression

import com.aicarcompanion.app.ai.AiChatClient
import com.aicarcompanion.app.ai.AiErrorClassifier
import com.aicarcompanion.app.ai.AiFailure
import com.aicarcompanion.app.ai.AiResult
import com.aicarcompanion.app.ai.AiStatus
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
import com.aicarcompanion.app.ui.home.aiChipText
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Regression tests for three AI error-handling defects found in the Phase 6 audit. Each test
 * failed before the fix in AiErrorClassifier:
 *
 * 1. A per-minute rate limit (HTTP 429) was reported as the *daily* quota ("usage limit reached").
 * 2. A slow network (socket timeout) was reported as "No internet connection".
 * 3. An overloaded model (HTTP 503, "try again later") was reported as a generic failure.
 *
 * Plus the home-screen chip said "Offline" for every unavailable reason.
 */
class TemporaryAiErrorsRegressionTest {

    // Real-world shapes of Gemini API errors (identifiers only; no keys or project data).
    private val perMinute = "429 RESOURCE_EXHAUSTED: Quota exceeded for quota metric 'Generate requests', " +
        "quotaId: GenerateRequestsPerMinutePerProjectPerModel-FreeTier. Please retry in 23s."
    private val perDay = "429 RESOURCE_EXHAUSTED: You exceeded your current quota. " +
        "quotaId: GenerateRequestsPerDayPerProjectPerModel-FreeTier"

    @Test fun `a per-minute rate limit is temporary, never the daily quota`() {
        assertEquals(AiFailure.BUSY, AiErrorClassifier.classify("QuotaExceededException", perMinute, false))
        assertEquals(AiFailure.BUSY, AiErrorClassifier.classify("ServerException", perMinute, false))
        assertEquals(AiFailure.BUSY, AiErrorClassifier.classify("ServerException", "HTTP 429 Too Many Requests", false))
        assertEquals(AiFailure.BUSY, AiErrorClassifier.classify("QuotaExceededException", null, false))
    }

    @Test fun `only an explicit daily quota is reported as the daily limit`() {
        assertEquals(AiFailure.QUOTA, AiErrorClassifier.classify("QuotaExceededException", perDay, false))
        assertEquals(AiFailure.QUOTA, AiErrorClassifier.classify("ServerException", "Daily quota exhausted (429)", false))
        assertTrue(AiFailure.QUOTA.userMessage.contains("day"))
        assertFalse(AiFailure.BUSY.userMessage.contains("day"))
        assertFalse(AiFailure.BUSY.userMessage.contains("limit was reached"))
    }

    @Test fun `a slow network is a timeout, not offline`() {
        assertEquals(AiFailure.TIMEOUT, AiErrorClassifier.classify("SocketTimeoutException", "timeout", causeIsNetwork = true))
        assertEquals(AiFailure.TIMEOUT, AiErrorClassifier.classify("ServerException", "Read timed out", causeIsNetwork = true))
        assertEquals(AiFailure.TIMEOUT, AiErrorClassifier.classify("RequestTimeoutException", null, false))
    }

    @Test fun `an overloaded model is temporary`() {
        assertEquals(AiFailure.BUSY, AiErrorClassifier.classify("ServerException", "503 UNAVAILABLE: The model is overloaded. Please try again later.", false))
        assertEquals(AiFailure.BUSY, AiErrorClassifier.classify("ServerException", "HTTP 503 Service Unavailable", false))
    }

    @Test fun `other server errors keep their meaning`() {
        assertEquals(AiFailure.OFFLINE, AiErrorClassifier.classify("UnknownHostException", "Unable to resolve host \"firebasevertexai.googleapis.com\"", true))
        assertEquals(AiFailure.OTHER, AiErrorClassifier.classify("ServerException", "500 INTERNAL: An internal error has occurred", false))
        assertEquals(AiFailure.OTHER, AiErrorClassifier.classify("SerializationException", "Unexpected JSON token", false))
        assertEquals(AiFailure.MODEL_UNAVAILABLE, AiErrorClassifier.classify("ServerException", "404 models/gemini-x is not found", false))
        assertEquals(AiFailure.APP_CHECK, AiErrorClassifier.classify("ServerException", "403 PERMISSION_DENIED", false))
    }

    @Test fun `the home chip names the real reason`() {
        assertEquals("Offline · using basic answers", aiChipText(AiStatus.Unavailable(AiFailure.OFFLINE)))
        assertEquals("Smarter answers busy · using basic answers", aiChipText(AiStatus.Unavailable(AiFailure.BUSY)))
        assertEquals("Smarter answers busy · using basic answers", aiChipText(AiStatus.Unavailable(AiFailure.TIMEOUT)))
        assertEquals("Daily AI limit reached · using basic answers", aiChipText(AiStatus.Unavailable(AiFailure.QUOTA)))
        assertEquals("App not verified yet · using basic answers", aiChipText(AiStatus.Unavailable(AiFailure.APP_CHECK)))
        for (reason in AiFailure.entries.filter { it != AiFailure.OFFLINE }) {
            assertFalse("$reason must not say Offline", aiChipText(AiStatus.Unavailable(reason)).startsWith("Offline"))
        }
        for (reason in AiFailure.entries.filter { it != AiFailure.QUOTA }) {
            assertFalse("$reason must not claim the daily limit", aiChipText(AiStatus.Unavailable(reason)).contains("Daily"))
        }
    }

    private var clock = Instant.parse("2026-11-17T12:00:00Z")

    @Test fun `after a temporary rate limit Penelope recovers and never mentions the daily limit`() = runTest {
        var calls = 0
        val client = object : AiChatClient {
            override val isConfigured = true
            override val modelName = "gemini-3.8-flash"
            override suspend fun generate(systemInstruction: String, history: List<AiTurn>, message: String): AiResult =
                if (++calls == 1) AiResult.Failure(AiErrorClassifier.classify("QuotaExceededException", perMinute, false))
                else AiResult.Reply("Back to normal.", modelName)
        }
        val context = VehicleContext(null, null, emptyList(), ObdConnectionState.Disconnected, emptyList())
        val parser = CommandParser { "Penelope" }
        val p = GeminiReasoningProvider(
            parser, RuleBasedReasoningProvider(parser, { context }, NoAdapterDiagnosticCommandHandler, GenericDtcCatalog),
            { context }, client, GenericDtcCatalog, { true }, now = { clock },
        )
        val first = p.respond(AssistantRequest("How often should I rotate my tires?", "Penelope", ExplanationLevel.EVERYDAY))
        assertEquals(AiStatus.Unavailable(AiFailure.BUSY), p.status.value)
        assertFalse("temporary error must not claim the daily limit: ${first.text}", first.text.contains("usage limit"))
        assertFalse(first.text.contains("resets within a day"))

        // Inside the provider's default 30 s wait Gemini isn't asked again (it would only fail again).
        clock = clock.plusSeconds(10)
        p.respond(AssistantRequest("And my wipers?", "Penelope", ExplanationLevel.EVERYDAY))
        assertEquals(1, calls)
        // After the wait, the next question works without restarting the app.
        clock = clock.plusSeconds(30)
        val second = p.respond(AssistantRequest("And my brake pads?", "Penelope", ExplanationLevel.EVERYDAY))
        assertEquals("${com.aicarcompanion.app.ai.AiNotices.RESTORED} Back to normal.", second.text)
        assertEquals(AiStatus.Ready("gemini-3.8-flash"), p.status.value)
    }
}
