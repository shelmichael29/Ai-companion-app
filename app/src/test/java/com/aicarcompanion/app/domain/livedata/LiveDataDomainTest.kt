package com.aicarcompanion.app.domain.livedata

import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import com.aicarcompanion.app.diagnostics.obd.ObdTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DashboardLayoutTest {
    private fun layout(vararg pids: Int) = DashboardLayout("l", "v", "Mine", pids.map { GaugeSpec(it) })
    private fun LayoutResult.layout() = (this as LayoutResult.Changed).layout

    @Test fun `a ninth gauge cannot be built or added`() {
        val full = layout(*LiveSensorCatalog.all.take(8).map { it.pid }.toIntArray())
        assertTrue(full.isFull)
        assertEquals(LayoutResult.Rejected(LayoutError.FULL), full.add(GaugeSpec(LiveSensorCatalog.all[8].pid)))
        val nine = LiveSensorCatalog.all.take(9).map { GaugeSpec(it.pid) }
        assertTrue(runCatching { DashboardLayout("l", "v", "x", nine) }.isFailure)
    }

    @Test fun `the same sensor cannot appear twice`() {
        assertEquals(LayoutResult.Rejected(LayoutError.DUPLICATE), layout(0x0C).add(GaugeSpec(0x0C)))
        assertTrue(runCatching { DashboardLayout("l", "v", "x", listOf(GaugeSpec(1), GaugeSpec(1))) }.isFailure)
    }

    @Test fun `add remove move resize restyle and unit`() {
        var l = layout(0x0C, 0x0D, 0x05)
        l = l.move(0, 2).layout()
        assertEquals(listOf(0x0D, 0x05, 0x0C), l.gauges.map { it.pid })
        l = l.resize(0x05, GaugeSize.LARGE).layout().restyle(0x05, GaugeStyle.ANALOG).layout().setUnit(0x05, UnitChoice.FAHRENHEIT).layout()
        assertEquals(GaugeSpec(0x05, GaugeStyle.ANALOG, GaugeSize.LARGE, UnitChoice.FAHRENHEIT), l.gauges[1])
        l = l.remove(0x0D).layout()
        assertEquals(listOf(0x05, 0x0C), l.gauges.map { it.pid })
        assertEquals(LayoutResult.Rejected(LayoutError.NOT_FOUND), l.remove(0x99))
        assertEquals(LayoutResult.Rejected(LayoutError.BAD_POSITION), l.move(0, 5))
    }

    @Test fun `rename trims and refuses blank`() {
        assertEquals("Track day", layout().rename("  Track day ").layout().name)
        assertEquals(LayoutResult.Rejected(LayoutError.BLANK_NAME), layout().rename("   "))
    }

    @Test fun `the default layout is within the limit and uses catalog sensors`() {
        val d = DashboardLayout.default("v")
        assertTrue(d.gauges.size <= DashboardLayout.MAX_GAUGES)
        assertTrue(d.gauges.all { LiveSensorCatalog.find(it.pid) != null })
    }
}

class UnitConversionTest {
    @Test fun `temperature speed pressure and flow`() {
        assertEquals(194.0, UnitConversion.convert(90.0, UnitChoice.FAHRENHEIT), 0.001)
        assertEquals(62.1371, UnitConversion.convert(100.0, UnitChoice.MPH), 0.001)
        assertEquals(14.5038, UnitConversion.convert(100.0, UnitChoice.PSI), 0.001)
        assertEquals(1.0, UnitConversion.convert(100.0, UnitChoice.BAR), 0.001)
        assertEquals(10.0, UnitConversion.convert(10.0, UnitChoice.GRAMS_PER_SECOND), 0.001)
        assertEquals(0.0794, UnitConversion.convert(60.0, UnitChoice.POUNDS_PER_MINUTE), 0.001)
    }

    @Test fun `choices match the base unit and start with the base unit`() {
        assertEquals(listOf(UnitChoice.CELSIUS, UnitChoice.FAHRENHEIT), UnitConversion.choicesFor("°C"))
        assertEquals(UnitChoice.KMH, UnitConversion.defaultFor("km/h"))
        assertEquals(listOf(UnitChoice.RPM), UnitConversion.choicesFor("rpm"))
        assertTrue(LiveSensorCatalog.all.all { UnitConversion.defaultFor(it.baseUnit) != null })
    }

    @Test fun `formatting keeps sensible precision`() {
        assertEquals("92", UnitConversion.format(92.2, UnitChoice.CELSIUS))
        assertEquals("14.1", UnitConversion.format(14.1, UnitChoice.VOLTS))
        assertEquals("1.01", UnitConversion.format(101.0, UnitChoice.BAR))
    }
}

class LiveSensorCatalogTest {
    @Test fun `every catalog sensor is a real PID with a matching label and unit`() {
        LiveSensorCatalog.all.forEach { s ->
            val pid = ObdPid.fromPid(s.pid)
            assertNotNull("0x${s.pid.toString(16)}", pid)
            assertEquals(pid!!.label, s.label)
            assertEquals(pid.unit, s.baseUnit)
            assertTrue(s.min < s.max)
        }
        assertEquals(LiveSensorCatalog.all.size, LiveSensorCatalog.all.map { it.pid }.toSet().size)
    }
}

class LiveDataStateMapperTest {
    private val now = Instant.parse("2026-11-21T12:00:00Z")
    private val connected = ObdConnectionState.Connected(ObdTransport.BLUETOOTH_CLASSIC, "OBDII", "CAN")
    private fun map(snap: LivePollSnapshot, conn: ObdConnectionState = connected, demo: Boolean = false, pids: Set<Int> = setOf(0x0C)) =
        LiveDataStateMapper.map(pids, snap, conn, now, demo)

    @Test fun `demo gives labelled sample values and never touches the snapshot`() {
        val s = map(LivePollSnapshot(mapOf(0x0C to PidSample.Ok(1.0, now))), ObdConnectionState.Disconnected, demo = true)
        assertTrue(s.getValue(0x0C) is SensorValueState.Sample)
    }

    @Test fun `ten seconds is live and a moment more is stale`() {
        assertTrue(map(LivePollSnapshot(mapOf(0x0C to PidSample.Ok(800.0, now.minusSeconds(10))))).getValue(0x0C) is SensorValueState.Live)
        val stale = map(LivePollSnapshot(mapOf(0x0C to PidSample.Ok(800.0, now.minusMillis(10_001))))).getValue(0x0C)
        assertEquals(800.0, (stale as SensorValueState.Stale).lastValue, 0.0)
    }

    @Test fun `states are distinct and a missing reading is never a number`() {
        assertEquals(SensorValueState.Disconnected, map(LivePollSnapshot(), ObdConnectionState.Disconnected).getValue(0x0C))
        assertEquals(SensorValueState.Unsupported, map(LivePollSnapshot(mapOf(0x0C to PidSample.Unsupported))).getValue(0x0C))
        assertTrue(map(LivePollSnapshot()).getValue(0x0C) is SensorValueState.TemporarilyUnavailable)
        assertTrue(map(LivePollSnapshot(mapOf(0x0C to PidSample.NoAnswer(now)))).getValue(0x0C) is SensorValueState.TemporarilyUnavailable)
        assertTrue(map(LivePollSnapshot(paused = true)).getValue(0x0C) is SensorValueState.Paused)
    }

    @Test fun `no answer after a good reading is stale not live`() {
        val good = PidSample.Ok(90.0, now.minusSeconds(1))
        val s = map(LivePollSnapshot(mapOf(0x0C to PidSample.NoAnswer(now, good)))).getValue(0x0C)
        assertTrue(s is SensorValueState.Stale)
    }

    @Test fun `a disconnect never keeps showing the old value`() {
        val s = map(LivePollSnapshot(mapOf(0x0C to PidSample.Ok(800.0, now))), ObdConnectionState.Disconnected).getValue(0x0C)
        assertEquals(SensorValueState.Disconnected, s)
    }

    @Test fun `ignition off explains itself`() {
        val s = map(LivePollSnapshot(), ObdConnectionState.Connected(ObdTransport.BLUETOOTH_CLASSIC, "OBDII", null)).getValue(0x0C)
        assertTrue((s as SensorValueState.TemporarilyUnavailable).reason.contains("ignition", ignoreCase = true))
    }
}
