package com.aicarcompanion.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DiagnosticTroubleCode
import com.aicarcompanion.app.domain.model.DtcStatus
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.ui.home.AdvancedTokens
import com.aicarcompanion.app.ui.home.codeCounts
import com.aicarcompanion.app.ui.home.displayPid
import com.aicarcompanion.app.ui.home.proVehicleLine
import com.aicarcompanion.app.ui.home.relevantReadings
import com.aicarcompanion.app.ui.home.spokenReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HomeLiveTest {
    private val t = Instant.parse("2026-11-16T21:40:00Z")
    private fun car(layout: EngineLayout, cyl: Int? = 6, code: String? = "N52B30") =
        Vehicle("v", 2011, "BMW", "528i", engine = EngineSpec(3.0, cyl, layout, engineCode = code))

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test fun vehicleLineNamesTheInlineSixAndNeverGuessesV6() {
        assertEquals("2011 BMW 528i · N52B30 inline-6", proVehicleLine(car(EngineLayout.INLINE)))
        assertEquals("2011 BMW 528i · N52B30 6-cyl", proVehicleLine(car(EngineLayout.UNKNOWN)))
        assertFalse(proVehicleLine(car(EngineLayout.UNKNOWN)).contains("V6"))
        assertEquals("2011 BMW 528i · N52B30 V6", proVehicleLine(car(EngineLayout.V)))
        assertEquals("2011 BMW 528i", proVehicleLine(car(EngineLayout.UNKNOWN, cyl = null, code = null)))
        assertEquals("No vehicle selected", proVehicleLine(null))
    }

    @Test fun spokenReadingNamesValueUnitAndPid() {
        val r = SensorReading("Coolant temperature", 92.0, "°C", t, DataOrigin.OBD_BLUETOOTH, "0105")
        assertEquals("Coolant temperature, 92 degrees Celsius, PID 05", spokenReading(r))
        assertEquals("05", displayPid("0105"))
        assertEquals("Coolant temperature, 90 degrees Celsius, sample data", spokenReading(r.copy(value = 90.0, origin = DataOrigin.SAMPLE, pid = null)))
    }

    @Test fun relevantReadingsAreLimitedAndOrderedByUsefulness() {
        val names = listOf(
            "Fuel level" to "012F", "Vehicle speed" to "010D", "Control module voltage" to "0142", "Engine speed" to "010C",
            "Coolant temperature" to "0105", "Throttle position" to "0111", "Intake air temperature" to "010F",
        )
        val readings = names.map { (n, p) -> SensorReading(n, 1.0, "", t, DataOrigin.OBD_BLUETOOTH, p) }
        val top = relevantReadings(readings, 6)
        assertEquals(6, top.size)
        assertEquals(listOf("Engine speed", "Coolant temperature", "Control module voltage"), top.take(3).map { it.name })
    }

    @Test fun codeCountsSplitByStatus() {
        fun c(s: DtcStatus) = DiagnosticTroubleCode("P0171", "x", status = s, origin = DataOrigin.OBD_BLUETOOTH)
        val counts = codeCounts(listOf(c(DtcStatus.STORED), c(DtcStatus.PENDING), c(DtcStatus.PENDING)))
        assertEquals(1, counts.stored)
        assertEquals(2, counts.pending)
        assertEquals(0, counts.permanent)
        assertTrue(counts.milLikely)
        assertFalse(codeCounts(listOf(c(DtcStatus.PENDING))).milLikely)
    }

    @Test fun advancedPaletteIsAccessibleInBothThemes() {
        for (k in listOf(AdvancedTokens.Dark, AdvancedTokens.Light)) {
            for (surface in listOf(k.background, k.panel)) {
                for (text in listOf(k.primary, k.secondary, k.tertiary, k.ok, k.warn, k.fault)) {
                    assertTrue("contrast ${contrast(text, surface)}", contrast(text, surface) >= 4.5)
                }
            }
            assertTrue(contrast(k.primary, k.raised) >= 4.5)
        }
    }

    @Test fun advancedPaletteHasNoPurpleAndKeepsTheOwnerColors() {
        assertEquals(Color(0xFF0A0A0A), AdvancedTokens.Dark.background)
        assertEquals(Color(0xFF4ADE80), AdvancedTokens.Dark.ok)
        assertEquals(Color(0xFFFBBF24), AdvancedTokens.Dark.warn)
        assertEquals(Color(0xFFF87171), AdvancedTokens.Dark.fault)
        listOf(AdvancedTokens.Dark.background, AdvancedTokens.Dark.panel, AdvancedTokens.Dark.raised, AdvancedTokens.Dark.hairline,
            AdvancedTokens.Dark.primary, AdvancedTokens.Dark.secondary, AdvancedTokens.Dark.tertiary).forEach {
            assertTrue("neutral", it.red == it.green && it.green == it.blue)
        }
    }
}
