@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.aicarcompanion.app.ai

import com.aicarcompanion.app.assistant.conversation.AssistantRequest
import com.aicarcompanion.app.assistant.conversation.CommandParser
import com.aicarcompanion.app.assistant.conversation.RuleBasedReasoningProvider
import com.aicarcompanion.app.assistant.conversation.VehicleContext
import com.aicarcompanion.app.assistant.persona.AssistantProfiles
import com.aicarcompanion.app.connectivity.ConnectionLog
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.diagnostics.dtc.GenericDtcCatalog
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.handsfree.android.AndroidHandsFreeCapabilities
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The selected personality reaches Gemini on every turn; deterministic routes are not personalised. */
class GeminiPersonaRoutingTest {
    private class FakeGemini : AiChatClient {
        val systems = mutableListOf<String>()
        override val isConfigured = true
        override val modelName = "test-model"
        override suspend fun generate(systemInstruction: String, history: List<AiTurn>, message: String): AiResult {
            systems += systemInstruction
            return AiResult.Reply("Sure thing.", "test-model")
        }
    }

    private val ctx = VehicleContext(null, null, emptyList(), ObdConnectionState.Disconnected, emptyList())
    private val parser = CommandParser { "Penelope" }

    private fun provider(gemini: FakeGemini) = GeminiReasoningProvider(
        parser, RuleBasedReasoningProvider(parser, { ctx }, com.aicarcompanion.app.assistant.conversation.NoAdapterDiagnosticCommandHandler, GenericDtcCatalog),
        { ctx }, gemini, GenericDtcCatalog, { true }, now = { Instant.parse("2026-11-24T12:00:00Z") },
        internetAvailable = { true }, log = ConnectionLog(now = { Instant.EPOCH }), sleep = { },
    )

    @Test fun `changing profile between turns changes the system instruction of the next turn`() = runTest {
        val g = FakeGemini()
        val p = provider(g)
        p.respond(AssistantRequest("Is synthetic oil worth it?", "Penelope", ExplanationLevel.EVERYDAY, AssistantProfiles.byId("luna")))
        p.respond(AssistantRequest("Is synthetic oil worth it?", "Ace", ExplanationLevel.EVERYDAY, AssistantProfiles.byId("ace")))
        assertEquals(2, g.systems.size)
        assertTrue(g.systems[0].contains(AssistantProfiles.byId("luna").instruction))
        assertTrue(g.systems[1].contains(AssistantProfiles.byId("ace").instruction))
        assertFalse(g.systems[1].contains(AssistantProfiles.byId("luna").instruction))
        assertTrue(g.systems[1].contains("You are \"Ace\""))
    }

    @Test fun `knowledge level changes are independent of the profile`() = runTest {
        val g = FakeGemini()
        val p = provider(g)
        p.respond(AssistantRequest("Is synthetic oil worth it?", "Nova", ExplanationLevel.ADVANCED, AssistantProfiles.byId("nova")))
        assertTrue(g.systems.single().contains("ADVANCED. Technical terms are fine"))
        assertTrue(g.systems.single().contains(AssistantProfiles.byId("nova").instruction))
    }

    @Test fun `deterministic safety routes never reach Gemini whatever the personality`() = runTest {
        val g = FakeGemini()
        val p = provider(g)
        val reply = p.respond(AssistantRequest("I smell gas", "Max", ExplanationLevel.EVERYDAY, AssistantProfiles.byId("max")))
        assertEquals(0, g.systems.size)
        assertFalse(reply.text.contains(AssistantProfiles.byId("max").instruction))
    }
}
