package com.aicarcompanion.app.ai

import com.aicarcompanion.app.assistant.conversation.VehicleContext
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.diagnostics.dtc.GenericDtcCatalog
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.MaintenanceRecord
import com.aicarcompanion.app.domain.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Owner decision 2026-11-21 (option 2): personal details are scrubbed before anything goes to Gemini. */
class PersonalDetailScrubberTest {

    private fun scrub(s: String) = PersonalDetailScrubber.scrub(s)
    private val removed = PersonalDetailScrubber.MARKER

    @Test fun `phone numbers in common formats are removed`() {
        for (phone in listOf("555-123-4567", "(555) 123-4567", "555.123.4567", "5551234567", "+1 555 123 4567", "555-1234")) {
            val out = scrub("Call Mike at $phone about the brakes")
            assertFalse("'$phone' must be removed: $out", out.contains(phone))
            assertTrue(out, out.contains(removed) && out.contains("about the brakes"))
        }
    }

    @Test fun `email addresses and links are removed`() {
        assertEquals("Receipt sent to $removed", scrub("Receipt sent to mike.smith+car@example.com"))
        assertEquals("Booked on $removed today", scrub("Booked on https://shop.example.com/booking?id=42 today"))
        assertEquals("See $removed", scrub("See www.example.com/page"))
    }

    @Test fun `street addresses are removed`() {
        for (addr in listOf("12 Oak St", "1600 Pennsylvania Ave", "4500 E Main Street", "77 Sunset Blvd.", "9 Old Mill Road")) {
            val out = scrub("Noise started after I drove to $addr yesterday")
            assertFalse("'$addr' must be removed: $out", out.contains(addr.trimEnd('.')))
            assertTrue(out, out.contains("yesterday"))
        }
    }

    @Test fun `VIN-like codes and labelled plates are removed`() {
        assertEquals("Recall check for $removed", scrub("Recall check for WBAFR1C56BC000001"))
        assertEquals("Recall check for $removed", scrub("Recall check for wbafr1c56bc000001"))
        assertEquals("New plate $removed fitted", scrub("New plate ABC 1234 fitted"))
        assertEquals("license plate number: $removed.", scrub("license plate number: 7XYZ123."))
        assertEquals("Tag $removed expired", scrub("Tag 8KLM552 expired"))
    }

    @Test fun `car words, codes, sizes, mileage and dates are kept`() {
        val keep = listOf(
            "Check engine light with P0455 and P0171",
            "N52B30 inline-six, 3.0L, 6 cylinders",
            "Oil change at 98,000 miles on 11/20/2026",
            "Brake pads 2026-11-21, 98000 miles",
            "4 Wheel Drive warning and 4 wheel drive noise",
            "3 way catalyst, 2 way valve, 12V battery at 14.2 V",
            "Grinding noise when I brake at 45 mph",
            "Replaced plate rack bushings",
        )
        for (k in keep) assertEquals(k, scrub(k))
    }

    @Test fun `scrubbing happens before the text is shortened, so no partial details slip through`() {
        val long = "x".repeat(150) + " call 555-123-4567"
        val ctx = context(symptoms = listOf(long))
        val prompt = instruction(ctx)
        assertFalse(prompt.contains("555-12"))
        assertFalse(prompt.contains("555"))
    }

    // ---- the facts block sent to Gemini ------------------------------------------------------------------

    private val car = Vehicle("bmw", 2011, "BMW", "528i", engine = EngineSpec(3.0, 6, EngineLayout.INLINE), vin = "WBAFR1C56BC000001")

    private fun context(service: List<MaintenanceRecord> = emptyList(), symptoms: List<String> = emptyList()) =
        VehicleContext(car, null, service, ObdConnectionState.Disconnected, recentSymptoms = symptoms)

    private fun instruction(ctx: VehicleContext) =
        PromptBuilder.systemInstruction("Penelope", ExplanationLevel.EVERYDAY, ctx, GenericDtcCatalog, emptySet(), Instant.parse("2026-11-21T12:00:00Z"))

    @Test fun `service titles and problem descriptions are scrubbed in what Gemini receives`() {
        val ctx = context(
            service = listOf(
                MaintenanceRecord("1", "bmw", LocalDate.of(2026, 10, 1), "Oil change at Joe's, call Mike 555-123-4567", odometerMiles = 98_000, origin = DataOrigin.USER_ENTERED),
                MaintenanceRecord("2", "bmw", LocalDate.of(2026, 9, 1), "Brake pads, receipt to mike@example.com", origin = DataOrigin.USER_ENTERED),
            ),
            symptoms = listOf("Squeal since I drove to 12 Oak St", "P0455 after fuel at www.example.com/gas"),
        )
        val prompt = instruction(ctx)
        for (secret in listOf("555-123-4567", "mike@example.com", "12 Oak St", "www.example.com", "WBAFR1C56BC000001")) {
            assertFalse("'$secret' must not reach Gemini", prompt.contains(secret))
        }
        for (kept in listOf("Oil change at Joe's", "98000 miles", "Brake pads", "Squeal since I drove to", "P0455")) {
            assertTrue("'$kept' should still reach Gemini", prompt.contains(kept))
        }
    }

    @Test fun `scrubbing never changes the saved records themselves`() {
        val title = "Oil change, call 555-123-4567"
        val record = MaintenanceRecord("1", "bmw", LocalDate.of(2026, 10, 1), title, origin = DataOrigin.USER_ENTERED)
        instruction(context(service = listOf(record)))
        assertEquals(title, record.title)
    }
}
