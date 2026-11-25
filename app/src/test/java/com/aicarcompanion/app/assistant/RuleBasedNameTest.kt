package com.aicarcompanion.app.assistant

import com.aicarcompanion.app.assistant.conversation.AssistantIntent
import com.aicarcompanion.app.assistant.conversation.AssistantRequest
import com.aicarcompanion.app.assistant.conversation.CommandParser
import com.aicarcompanion.app.assistant.conversation.NoAdapterDiagnosticCommandHandler
import com.aicarcompanion.app.assistant.conversation.RuleBasedReasoningProvider
import com.aicarcompanion.app.assistant.conversation.VehicleContext
import com.aicarcompanion.app.diagnostics.dtc.GenericDtcCatalog
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBasedNameTest {
    private val ctx = VehicleContext(null, null, emptyList(), ObdConnectionState.Disconnected, emptyList())

    private fun rules(name: String): RuleBasedReasoningProvider {
        val parser = CommandParser { name }
        return RuleBasedReasoningProvider(parser, { ctx }, NoAdapterDiagnosticCommandHandler, GenericDtcCatalog)
    }

    @Test fun `greeting uses the profile name`() = runTest {
        assertTrue(rules("Ace").respond(AssistantRequest("hello", "Ace")).text.startsWith("Hi, I'm Ace."))
    }

    @Test fun `greeting uses a custom name`() = runTest {
        assertTrue(rules("Jarvis").respond(AssistantRequest("hello", "Jarvis")).text.startsWith("Hi, I'm Jarvis."))
    }

    @Test fun `a hyphenated name is recognised when spoken as separate words`() {
        val parser = CommandParser { "Janay-Latrice" }
        assertEquals("what is my engine temperature", parser.normalize("Hey Janay Latrice, what is my engine temperature"))
        assertEquals("hello", parser.normalize("Janay-Latrice hello"))
        assertTrue(parser.parse("hey janay latrice what's my engine temperature") is AssistantIntent.LiveValue)
    }

    @Test fun `the default parser name is Penelope`() {
        assertEquals("hello", CommandParser().normalize("hey penelope hello"))
    }
}
