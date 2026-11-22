package com.aicarcompanion.app.ui.livedata

import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdTransport
import com.aicarcompanion.app.domain.livedata.LiveSensorCatalog
import com.aicarcompanion.app.domain.livedata.SensorValueState
import com.aicarcompanion.app.domain.livedata.UnitChoice
import com.aicarcompanion.app.ui.navigation.LiveDataStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GaugeSemanticsTest {
    private val now = Instant.parse("2026-11-21T12:00:00Z")
    private val coolant = LiveSensorCatalog.find(0x05)!!

    @Test fun `live description is spoken in full`() {
        assertEquals(
            "Coolant temperature, 92 degrees Celsius, live",
            GaugeSemantics.gaugeDescription(coolant, SensorValueState.Live(92.0, now), null, now),
        )
        assertEquals(
            "Coolant temperature, 198 degrees Fahrenheit, live",
            GaugeSemantics.gaugeDescription(coolant, SensorValueState.Live(92.0, now), UnitChoice.FAHRENHEIT, now),
        )
    }

    @Test fun `sample data says it is not from the vehicle`() {
        val d = GaugeSemantics.gaugeDescription(coolant, SensorValueState.Sample(90.0, now), null, now)
        assertTrue(d, d.contains("sample data") && d.contains("not from your vehicle"))
    }

    @Test fun `non-live states name the state and never carry a number`() {
        val states = listOf(
            SensorValueState.Unsupported to "not supported",
            SensorValueState.TemporarilyUnavailable("No answer") to "temporarily unavailable",
            SensorValueState.Disconnected to "disconnected",
            SensorValueState.Paused("x") to "paused",
        )
        states.forEach { (s, word) ->
            val d = GaugeSemantics.gaugeDescription(coolant, s, null, now)
            assertTrue(d, d.contains(word))
            assertTrue(d, d.none { it.isDigit() })
            assertNull(GaugeSemantics.currentValue(s))
        }
    }

    @Test fun `stale says stale and how old`() {
        val s = SensorValueState.Stale(88.0, now.minusSeconds(12))
        assertEquals("Stale · 12 s ago", GaugeSemantics.stateText(s, now))
        assertTrue(GaugeSemantics.gaugeDescription(coolant, s, null, now).contains("stale"))
        assertNull(GaugeSemantics.currentValue(s))
    }

    @Test fun `unit choice that does not fit the sensor is ignored`() {
        assertEquals(UnitChoice.CELSIUS, GaugeSemantics.unitFor(coolant, UnitChoice.PSI))
    }

    @Test fun `range status uses the sensor limits`() {
        assertEquals("High", GaugeSemantics.rangeStatus(coolant, SensorValueState.Live(115.0, now)))
        assertEquals("Normal", GaugeSemantics.rangeStatus(coolant, SensorValueState.Live(90.0, now)))
        assertNull(GaugeSemantics.rangeStatus(coolant, SensorValueState.Disconnected))
    }

    @Test fun `banner reflects connection and states`() {
        val connected = ObdConnectionState.Connected(ObdTransport.BLUETOOTH_CLASSIC, "OBDII", "CAN")
        assertEquals(LiveDataStatus.NoVehicle, GaugeSemantics.bannerStatus(false, connected, emptyMap()))
        assertEquals(LiveDataStatus.Sample, GaugeSemantics.bannerStatus(true, ObdConnectionState.Disconnected, mapOf(1 to SensorValueState.Sample(1.0, now))))
        assertEquals(LiveDataStatus.Disconnected, GaugeSemantics.bannerStatus(true, ObdConnectionState.Disconnected, emptyMap()))
        assertEquals(LiveDataStatus.Live, GaugeSemantics.bannerStatus(true, connected, mapOf(1 to SensorValueState.Live(1.0, now))))
        assertEquals(LiveDataStatus.WaitingForData, GaugeSemantics.bannerStatus(true, connected, mapOf(1 to SensorValueState.Unsupported)))
    }
}
