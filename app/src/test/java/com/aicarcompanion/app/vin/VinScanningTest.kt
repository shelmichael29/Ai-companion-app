package com.aicarcompanion.app.vin

import com.aicarcompanion.app.data.vehicledb.VinValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Turning recognized camera text into a VIN candidate, and deciding when it is steady enough to show. */
class VinScanningTest {

    private val vin = "WBAFR1C56BC000001" // synthetic, valid check digit
    private val box = ScanBox(10, 20, 410, 70)

    private fun line(text: String) = RecognizedLine(text, box)

    /** A different VIN with a correct check digit. */
    private fun otherVin(): String {
        val body = "1HGCM82033A004352".toCharArray()
        body[8] = VinValidator.checkDigit(String(body))
        return String(body)
    }

    @Test fun `a clean VIN line becomes one valid candidate`() {
        val c = VinTextExtractor.candidates(listOf(line(vin))).single()
        assertEquals(vin, c.vin)
        assertTrue(c.checkDigitValid)
        assertEquals(0, c.correctedChars)
        assertFalse(c.uncertain)
        assertEquals(box, c.box)
    }

    @Test fun `label, spacing and lowercase are tolerated`() {
        assertEquals(vin, VinTextExtractor.candidates(listOf(line("VIN: wbafr1c5 6bc000001"))).single().vin)
        assertEquals(vin, VinTextExtractor.candidates(listOf(line("VIN $vin"))).single().vin)
        assertEquals(vin, VinTextExtractor.candidates(listOf(line("VIN$vin"))).single().vin)
    }

    @Test fun `I, O and Q are always misreads and are corrected and flagged`() {
        val misread = vin.replace('1', 'I').replace('0', 'O') // WBAFRIC56BCOOOOO1 style
        val c = VinTextExtractor.candidates(listOf(line(misread))).single()
        assertEquals(vin, c.vin)
        assertTrue(c.correctedChars > 0)
        assertTrue("a corrected VIN must be treated as uncertain", c.uncertain)
        val q = VinTextExtractor.candidates(listOf(line(vin.replace("000001", "Q00001"))))
        assertTrue(q.all { it.vin.none { ch -> ch == 'Q' } })
    }

    @Test fun `a failed check digit is kept but flagged uncertain, not silently accepted or dropped`() {
        val wrong = "WBAFR1C50BC000001"
        val c = VinTextExtractor.candidates(listOf(line(wrong))).single()
        assertEquals(wrong, c.vin)
        assertFalse(c.checkDigitValid)
        assertTrue(c.uncertain)
    }

    @Test fun `17-character windows inside longer text are not VINs`() {
        assertTrue(VinTextExtractor.candidates(listOf(line("X$vin" + "Y"))).isEmpty())
        assertTrue(VinTextExtractor.candidates(listOf(line("GROSS VEHICLE WEIGHT RATING 1950 KG 4300 LB"))).isEmpty())
        assertTrue(VinTextExtractor.candidates(listOf(line("WBAFR1C56BC00000"))).isEmpty()) // 16
    }

    @Test fun `the same VIN seen twice in a frame is one candidate, preferring the uncorrected sighting`() {
        val list = VinTextExtractor.candidates(listOf(line(vin.replace('1', 'I')), line(vin)))
        assertEquals(1, list.size)
        assertEquals(0, list.single().correctedChars)
    }

    @Test fun `two different VINs in one frame are both reported`() {
        assertEquals(2, VinTextExtractor.candidates(listOf(line(vin), line(otherVin()))).size)
    }

    // ---- tracker --------------------------------------------------------------------------------------------

    private fun cand(v: String = vin) = VinTextExtractor.candidates(listOf(line(v)))

    @Test fun `nothing in view means searching`() {
        assertEquals(ScanState.Searching, VinScanTracker().observe(emptyList()))
    }

    @Test fun `a VIN locks only after being seen steadily in several frames`() {
        val t = VinScanTracker(framesToLock = 3)
        assertTrue(t.observe(cand()) is ScanState.Reading)
        assertTrue(t.observe(cand()) is ScanState.Reading)
        val locked = t.observe(cand()) as ScanState.Locked
        assertEquals(vin, locked.candidate.vin)
    }

    @Test fun `a one-frame misread never locks`() {
        val t = VinScanTracker(framesToLock = 3)
        repeat(2) { t.observe(cand()) }
        // Two sightings so far; a different VIN in the third frame must not complete the lock.
        assertFalse(t.observe(cand(otherVin())) is ScanState.Locked)
    }

    @Test fun `competing VINs are a conflict, never a pick`() {
        val t = VinScanTracker(framesToLock = 3)
        val both = cand() + cand(otherVin())
        t.observe(both)
        val state = t.observe(both)
        assertEquals(ScanState.Conflict, state)
    }

    @Test fun `reset forgets earlier sightings`() {
        val t = VinScanTracker(framesToLock = 2)
        t.observe(cand()); t.observe(cand())
        t.reset()
        assertTrue(t.observe(cand()) is ScanState.Reading)
    }

    // ---- geometry ---------------------------------------------------------------------------------------------

    @Test fun `box maps onto a preview that crops to fill`() {
        // 1920x1080 image shown in a 540x1080 (portrait) view: scale 1.0, cropped horizontally.
        val r = ScanGeometry.toView(ScanBox(960, 100, 1060, 150), 1920, 1080, 540f, 1080f)
        assertEquals(960f - 690f, r.left, 0.01f) // (540 - 1920) / 2 = -690 of horizontal crop
        assertEquals(100f, r.top, 0.01f)
        assertEquals(100f, r.right - r.left, 0.01f)
    }

    @Test fun `degenerate sizes give an empty rectangle`() {
        val r = ScanGeometry.toView(ScanBox(0, 0, 10, 10), 0, 0, 100f, 100f)
        assertEquals(ScanGeometry.Rect(0f, 0f, 0f, 0f), r)
    }
}
