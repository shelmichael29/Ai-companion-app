package com.aicarcompanion.app.assistant

import com.aicarcompanion.app.assistant.persona.VoiceGender
import com.aicarcompanion.app.assistant.voice.VoiceConfig
import com.aicarcompanion.app.assistant.voice.VoiceConfigHolder
import com.aicarcompanion.app.assistant.voice.VoiceRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceConfigHolderTest {
    private val a = VoiceConfig(rate = 1.0f, voice = VoiceRequest.Gendered(VoiceGender.FEMALE, 0))
    private val b = VoiceConfig(rate = 1.3f, pitch = 0.9f, engine = "com.x", voice = VoiceRequest.Gendered(VoiceGender.MALE, 1))
    private val previewCfg = VoiceConfig(rate = 0.8f, voice = VoiceRequest.Gendered(VoiceGender.MALE, 3))

    @Test fun `a settings change during a preview is not lost when the preview ends`() {
        val h = VoiceConfigHolder()
        h.configure(a, busy = false)
        h.beginPreview(previewCfg)
        assertEquals(previewCfg.voice, h.effective.voice)
        val applyNow = h.configure(b, busy = false)
        assertFalse("must wait until the preview ends", applyNow)
        assertTrue(h.pending)
        h.endPreview()
        assertEquals(b, h.effective)
        assertTrue("the engine must be told about B", h.pending)
        h.markApplied()
        assertFalse(h.pending)
    }

    @Test fun `a preview never changes the engine`() {
        val h = VoiceConfigHolder()
        h.configure(b, busy = false)
        h.beginPreview(previewCfg.copy(engine = "other"))
        assertEquals("com.x", h.effective.engine)
    }

    @Test fun `configure while speaking is deferred`() {
        val h = VoiceConfigHolder()
        assertFalse(h.configure(a, busy = true))
        assertTrue(h.pending)
        assertTrue(h.configure(b, busy = false))
        assertFalse(h.pending)
    }

    @Test fun `ending a preview restores the configured voice and asks for re-apply`() {
        val h = VoiceConfigHolder()
        h.configure(a, busy = false)
        h.beginPreview(previewCfg); h.endPreview()
        assertEquals(a, h.effective)
        assertTrue(h.pending)
    }

    @Test fun `a failed network voice stays suspended until the internet returns`() {
        val h = VoiceConfigHolder()
        h.configure(a, busy = false)
        h.suspendNetworkVoice()
        assertFalse(h.networkVoiceAllowed(online = false))
        assertFalse("still offline on the next utterance", h.networkVoiceAllowed(online = false))
        assertTrue(h.networkVoiceAllowed(online = true))
        assertTrue("re-apply to pick the online voice again", h.pending)
        assertTrue(h.networkVoiceAllowed(online = false))
    }

    @Test fun `choosing a voice clears the suspension`() {
        val h = VoiceConfigHolder()
        h.suspendNetworkVoice()
        h.configure(b, busy = false)
        assertTrue(h.networkVoiceAllowed(online = false))
    }
}
