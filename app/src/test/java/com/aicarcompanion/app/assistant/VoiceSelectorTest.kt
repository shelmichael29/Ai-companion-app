package com.aicarcompanion.app.assistant

import com.aicarcompanion.app.assistant.persona.VoiceGender
import com.aicarcompanion.app.assistant.voice.VoiceRequest
import com.aicarcompanion.app.assistant.voice.VoiceResolution
import com.aicarcompanion.app.assistant.voice.VoiceSelector
import com.aicarcompanion.app.assistant.voice.VoiceTuning.RawVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VoiceSelectorTest {
    private val us = Locale.US

    private fun v(name: String, locale: Locale = us, net: Boolean = false, q: Int = 400, features: Set<String> = emptySet()) =
        RawVoice(name, locale, net, q, features)

    private val google = listOf(
        v("en-us-x-sfg-local"), v("en-us-x-tpf-local"), v("en-us-x-iob-local"), v("en-us-x-iog-local"),
        v("en-us-x-iom-local"), v("en-us-x-iol-local"), v("en-us-x-tpd-local"),
    )

    private fun gendered(g: VoiceGender, slot: Int, voices: List<RawVoice>) =
        VoiceSelector.resolve(VoiceRequest.Gendered(g, slot), voices, us)

    @Test fun `gender comes from features Samsung names or the Google table and is otherwise unknown`() {
        assertEquals(VoiceGender.FEMALE, VoiceSelector.genderOf(v("x", features = setOf("gender=female"))))
        assertEquals(VoiceGender.MALE, VoiceSelector.genderOf(v("x", features = setOf("male"))))
        assertEquals(VoiceGender.FEMALE, VoiceSelector.genderOf(v("en-US-SMTf00")))
        assertEquals(VoiceGender.MALE, VoiceSelector.genderOf(v("en-US-smtm01")))
        assertEquals(VoiceGender.FEMALE, VoiceSelector.genderOf(v("en-us-x-sfg-local")))
        assertEquals(VoiceGender.MALE, VoiceSelector.genderOf(v("en-us-x-iol-local")))
        assertNull(VoiceSelector.genderOf(v("en-us-x-zzz-local")))
        assertNull(VoiceSelector.genderOf(v("mystery voice")))
    }

    @Test fun `different slots give different voices when enough are installed`() {
        val names = (0 until 4).map { (gendered(VoiceGender.FEMALE, it, google) as VoiceResolution.Voice).voiceName }
        assertEquals(4, names.toSet().size)
        val male = (0 until 3).map { (gendered(VoiceGender.MALE, it, google) as VoiceResolution.Voice).voiceName }
        assertEquals(3, male.toSet().size)
    }

    @Test fun `slots wrap and are marked shared when fewer voices than profiles`() {
        val a = gendered(VoiceGender.MALE, 0, google) as VoiceResolution.Voice
        val wrapped = gendered(VoiceGender.MALE, 3, google) as VoiceResolution.Voice
        assertEquals(a.voiceName, wrapped.voiceName)
        assertTrue(wrapped.shared)
        assertEquals(VoiceGender.MALE, wrapped.gender)
    }

    @Test fun `no male voice falls back to the default with an honest reason`() {
        val onlyFemale = google.filter { VoiceSelector.genderOf(it) == VoiceGender.FEMALE }
        val r = gendered(VoiceGender.MALE, 0, onlyFemale)
        assertEquals("No male voice is installed on this phone", (r as VoiceResolution.FallbackDefault).reason)
        val noFemale = google.filter { VoiceSelector.genderOf(it) == VoiceGender.MALE }
        assertEquals("No female voice is installed on this phone", (gendered(VoiceGender.FEMALE, 0, noFemale) as VoiceResolution.FallbackDefault).reason)
    }

    @Test fun `a gendered request never returns an unknown-gender voice`() {
        val voices = listOf(v("mystery-a"), v("mystery-b"))
        assertTrue(gendered(VoiceGender.FEMALE, 0, voices) is VoiceResolution.FallbackDefault)
        assertTrue(gendered(VoiceGender.MALE, 0, voices) is VoiceResolution.FallbackDefault)
    }

    @Test fun `not installed voices and other languages are ignored`() {
        val voices = listOf(
            v("en-us-x-sfg-local", features = setOf("notInstalled")),
            v("fr-fr-x-vlf-local", Locale.FRANCE, features = setOf("female")),
        )
        assertTrue(gendered(VoiceGender.FEMALE, 0, voices) is VoiceResolution.FallbackDefault)
    }

    @Test fun `offline voices come before network voices and network is used only if nothing else`() {
        val voices = listOf(v("net-female", net = true, features = setOf("female")), v("off-female", features = setOf("female")))
        val r = gendered(VoiceGender.FEMALE, 0, voices) as VoiceResolution.Voice
        assertEquals("off-female", r.voiceName)
        assertFalse(r.requiresNetwork)
        val netOnly = gendered(VoiceGender.FEMALE, 0, listOf(v("net-female", net = true, features = setOf("female")))) as VoiceResolution.Voice
        assertTrue(netOnly.requiresNetwork)
        assertEquals("Female voice 1 · needs internet", netOnly.label)
    }

    @Test fun `the result does not depend on the order the engine lists voices`() {
        val a = gendered(VoiceGender.FEMALE, 2, google)
        val b = gendered(VoiceGender.FEMALE, 2, google.reversed())
        val c = gendered(VoiceGender.FEMALE, 2, google.shuffled(java.util.Random(7)))
        assertEquals(a, b)
        assertEquals(a, c)
    }

    @Test fun `country match comes first then quality`() {
        val voices = listOf(
            v("en-gb-female", Locale.UK, q = 500, features = setOf("female")),
            v("en-us-female-low", q = 200, features = setOf("female")),
            v("en-us-female-high", q = 400, features = setOf("female")),
        )
        assertEquals("en-us-female-high", (gendered(VoiceGender.FEMALE, 0, voices) as VoiceResolution.Voice).voiceName)
    }

    @Test fun `a missing chosen voice falls back to the personality voice and says so`() {
        val req = VoiceRequest.Named("gone-voice", VoiceRequest.Gendered(VoiceGender.FEMALE, 0))
        val r = VoiceSelector.resolve(req, google, us) as VoiceResolution.Voice
        assertEquals("Your chosen voice isn't installed anymore", r.notice)
        assertEquals(VoiceGender.FEMALE, r.gender)
        val none = VoiceSelector.resolve(req, emptyList(), us) as VoiceResolution.FallbackDefault
        assertTrue(none.reason.startsWith("Your chosen voice isn't installed anymore"))
    }

    @Test fun `a named installed voice is used and unknown gender is labelled unlabeled`() {
        val voices = listOf(v("mystery-a"), v("en-us-x-sfg-local"))
        val r = VoiceSelector.resolve(VoiceRequest.Named("mystery-a", VoiceRequest.Gendered(VoiceGender.FEMALE, 0)), voices, us) as VoiceResolution.Voice
        assertEquals("mystery-a", r.voiceName)
        assertNull(r.gender)
        assertEquals("Unlabeled voice 1 · works offline", r.label)
    }

    @Test fun `labels say female or male only when the phone does`() {
        val f = gendered(VoiceGender.FEMALE, 1, google) as VoiceResolution.Voice
        assertTrue(f.label.startsWith("Female voice 2 · works offline"))
        val m = gendered(VoiceGender.MALE, 0, google) as VoiceResolution.Voice
        assertTrue(m.label.startsWith("Male voice 1"))
    }

    @Test fun `engine default never claims a gender`() {
        val r = VoiceSelector.resolve(VoiceRequest.EngineDefault, google, us)
        assertTrue(r is VoiceResolution.FallbackDefault)
    }
}
