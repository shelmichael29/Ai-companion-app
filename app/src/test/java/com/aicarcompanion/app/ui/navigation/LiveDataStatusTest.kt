package com.aicarcompanion.app.ui.navigation

import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdTransport
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.SensorReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LiveDataStatusTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private val connected = ObdConnectionState.Connected(ObdTransport.BLUETOOTH_CLASSIC, "Adapter")
    private fun reading(ageSeconds: Long, origin: DataOrigin = DataOrigin.OBD_BLUETOOTH) =
        SensorReading("Engine speed", 800.0, "rpm", now.minusSeconds(ageSeconds), origin, "010C")

    @Test fun `no vehicle`() =
        assertEquals(LiveDataStatus.NoVehicle, LiveDataStatus.of(false, listOf(reading(0)), connected, now))

    @Test fun `disconnected without readings shows no values`() {
        val s = LiveDataStatus.of(true, emptyList(), ObdConnectionState.Disconnected, now)
        assertEquals(LiveDataStatus.Disconnected, s)
        assertFalse(s.showsValues)
    }

    @Test fun `disconnected hides old real readings too`() {
        val s = LiveDataStatus.of(true, listOf(reading(1)), ObdConnectionState.Disconnected, now)
        assertFalse(s.showsValues)
    }

    @Test fun `connected without readings waits`() =
        assertEquals(LiveDataStatus.WaitingForData, LiveDataStatus.of(true, emptyList(), connected, now))

    @Test fun `connected and fresh is live`() {
        val s = LiveDataStatus.of(true, listOf(reading(3), reading(9)), connected, now)
        assertEquals(LiveDataStatus.Live, s)
        assertTrue(s.showsValues)
    }

    @Test fun `older than ten seconds is stale`() {
        val s = LiveDataStatus.of(true, listOf(reading(11)), connected, now)
        assertEquals(LiveDataStatus.Stale(now.minusSeconds(11)), s)
        assertEquals(LiveDataStatus.Live, LiveDataStatus.of(true, listOf(reading(10)), connected, now))
    }

    @Test fun `sample readings are labeled sample even when disconnected`() {
        val s = LiveDataStatus.of(true, listOf(reading(500, DataOrigin.SAMPLE)), ObdConnectionState.Disconnected, now)
        assertEquals(LiveDataStatus.Sample, s)
    }

    @Test fun `age labels`() {
        assertEquals("just now", LiveDataStatus.ageLabel(now, now))
        assertEquals("5 s ago", LiveDataStatus.ageLabel(now.minusSeconds(5), now))
        assertEquals("2 min ago", LiveDataStatus.ageLabel(now.minusSeconds(130), now))
    }
}
