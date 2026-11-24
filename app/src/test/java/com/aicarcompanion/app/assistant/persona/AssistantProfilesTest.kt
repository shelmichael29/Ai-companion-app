package com.aicarcompanion.app.assistant.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantProfilesTest {
    private val all = AssistantProfiles.all

    @Test fun `there are ten profiles with stable ids in a fixed order`() {
        assertEquals(
            listOf("penelope", "janay_latrice", "nova", "luna", "sage", "aurora", "oliver", "atlas", "max", "ace"),
            all.map { it.id },
        )
        assertEquals(
            listOf("Penelope", "Janay-Latrice", "Nova", "Luna", "Sage", "Aurora", "Oliver", "Atlas", "Max", "Ace"),
            all.map { it.name },
        )
    }

    @Test fun `Penelope is first and the default`() {
        assertEquals("penelope", AssistantProfiles.DEFAULT_ID)
        assertSame(all.first(), AssistantProfiles.default)
    }

    @Test fun `six female and four male profiles`() {
        assertEquals(6, all.count { it.voiceGender == VoiceGender.FEMALE })
        assertEquals(4, all.count { it.voiceGender == VoiceGender.MALE })
    }

    @Test fun `voice slots are unique within a gender`() {
        VoiceGender.entries.forEach { g ->
            val slots = all.filter { it.voiceGender == g }.map { it.voiceSlot }
            assertEquals(slots.size, slots.toSet().size)
        }
    }

    @Test fun `unknown and null ids fall back to Penelope`() {
        assertSame(AssistantProfiles.default, AssistantProfiles.byId(null))
        assertSame(AssistantProfiles.default, AssistantProfiles.byId("nope"))
        assertEquals("ace", AssistantProfiles.byId("ace").id)
    }

    @Test fun `profile names pass the name policy unchanged`() {
        all.forEach { assertEquals(it.name, AssistantNamePolicy.sanitize(it.name)) }
    }

    @Test fun `every profile has a summary and a short tone-only instruction`() {
        all.forEach {
            assertTrue(it.summary.isNotBlank())
            assertNotNull(it.instruction)
            assertTrue(it.instruction.length < 200)
            assertTrue("no newline", '\n' !in it.instruction)
        }
    }
}
