package com.aicarcompanion.app.assistant

import com.aicarcompanion.app.assistant.conversation.AssistantIntent
import com.aicarcompanion.app.assistant.conversation.CommandParser
import com.aicarcompanion.app.assistant.conversation.LiveMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandParserTest {
    private val parser = CommandParser { "Penelope" }

    @Test fun `required example requests`() {
        assertEquals(AssistantIntent.WhatVehicle, parser.parse("Penelope, what vehicle am I driving?"))
        assertEquals(AssistantIntent.EngineInfo, parser.parse("Tell me about my engine."))
        assertEquals(AssistantIntent.ReadTroubleCodes, parser.parse("Read my trouble codes."))
        assertEquals(AssistantIntent.ExplainCode(null), parser.parse("Explain this diagnostic code."))
        assertEquals(AssistantIntent.LiveValue(LiveMetric.COOLANT_TEMP), parser.parse("What's my coolant temperature?"))
        assertEquals(AssistantIntent.Help, parser.parse("What can you do?"))
    }

    @Test fun `other useful phrasings`() {
        assertEquals(AssistantIntent.ReadTroubleCodes, parser.parse("why is my check engine light on"))
        assertEquals(AssistantIntent.LiveValue(LiveMetric.ENGINE_RPM), parser.parse("what are my RPMs"))
        assertEquals(AssistantIntent.LiveValue(LiveMetric.MODULE_VOLTAGE), parser.parse("how's my battery voltage"))
        assertEquals(AssistantIntent.LiveValue(LiveMetric.VEHICLE_SPEED), parser.parse("how fast am I going"))
        assertEquals(AssistantIntent.ConnectionStatus, parser.parse("are you connected to the adapter"))
        assertEquals(AssistantIntent.LastService, parser.parse("when was my last oil change"))
        assertEquals(AssistantIntent.Mileage, parser.parse("what's the mileage"))
        assertEquals(AssistantIntent.Greeting, parser.parse("Hey Penelope"))
    }

    @Test fun `engine temperature means coolant, not engine description`() {
        assertEquals(AssistantIntent.LiveValue(LiveMetric.COOLANT_TEMP), parser.parse("what's the engine temperature"))
    }

    @Test fun `trouble codes in many spoken forms`() {
        assertEquals(AssistantIntent.ExplainCode("P0420"), parser.parse("explain P0420"))
        assertEquals(AssistantIntent.ExplainCode("P0420"), parser.parse("what does p 0420 mean"))
        assertEquals(AssistantIntent.ExplainCode("P0420"), parser.parse("explain code P zero four two zero"))
        assertEquals(AssistantIntent.ExplainCode("P0420"), parser.parse("what is p oh four twenty"))
        assertEquals(AssistantIntent.ExplainCode("P0133"), parser.parse("Penelope explain p0 133"))
        assertEquals(AssistantIntent.ExplainCode("U0100"), parser.parse("what's u0100"))
        assertEquals(AssistantIntent.ExplainCode("P2A00"), parser.parse("explain p2a00"))
    }

    @Test fun `ordinary words are not mistaken for codes`() {
        assertNull(parser.extractTroubleCode(parser.normalize("please check my car battery")))
        assertNull(parser.extractTroubleCode(parser.normalize("can you tell me about the brakes")))
        assertNull(parser.extractTroubleCode(parser.normalize("are you for real")))
    }

    @Test fun `custom assistant name is stripped`() {
        val custom = CommandParser { "Ada" }
        assertEquals(AssistantIntent.EngineInfo, custom.parse("Hey Ada, tell me about my engine"))
        assertEquals("tell me about my engine", custom.normalize("Ada tell me about my engine"))
    }

    @Test fun `unknown requests are reported as unknown`() {
        assertEquals(AssistantIntent.Unknown("order me a pizza"), parser.parse("order me a pizza"))
    }
}
