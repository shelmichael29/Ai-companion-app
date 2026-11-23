package com.aicarcompanion.app.assistant.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UtteranceIdentityTest {
    @Test fun `callbacks from a previous or similarly prefixed request cannot complete the active speech`() {
        assertTrue(VoiceTuning.isCurrentUtterance("abc", "abc-0#last"))
        assertFalse(VoiceTuning.isCurrentUtterance("abc", "old-0#last"))
        assertFalse(VoiceTuning.isCurrentUtterance("abc", "abcd-0#last"))
        assertFalse(VoiceTuning.isCurrentUtterance("abc", "abc"))
    }
}
