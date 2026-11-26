package com.aicarcompanion.app.dashboard

import com.aicarcompanion.app.diagnostics.bluetooth.ConnectFailure
import com.aicarcompanion.app.diagnostics.obd.ConnectPhase
import com.aicarcompanion.app.diagnostics.obd.LinkHealth
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdTransport
import com.aicarcompanion.app.domain.livedata.LivePollSnapshot
import com.aicarcompanion.app.domain.livedata.LiveDataStateMapper
import com.aicarcompanion.app.domain.livedata.PidSample
import com.aicarcompanion.app.domain.livedata.SensorValueState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class DiyConnectionStageTest {
    private val now = Instant.parse("2026-11-26T12:00:00Z")
    private val bt = ObdTransport.BLUETOOTH_CLASSIC
    private fun connected(protocol: String? = "ISO 15765-4 CAN", health: LinkHealth = LinkHealth.OK, name: String = "OBDII", version: String? = "ELM327 v1.5") =
        ObdConnectionState.Connected(bt, name, protocol, version, health)

    private fun map(state: ObdConnectionState, sensors: Collection<SensorValueState> = emptyList()) =
        DiyConnectionMapper.map(state, sensors, now)

    @Test fun `disconnected is disconnected and nothing is in progress`() {
        val s = map(ObdConnectionState.Disconnected)
        assertEquals(DiyConnectionStage.DISCONNECTED, s.stage)
        assertFalse(s.inProgress)
        assertEquals(StepState.PENDING, s.link)
    }

    @Test fun `opening the link is still disconnected but in progress`() {
        val s = map(ObdConnectionState.Connecting(bt, "OBDII", ConnectPhase.OPENING_LINK))
        assertEquals(DiyConnectionStage.DISCONNECTED, s.stage)
        assertTrue(s.inProgress)
        assertEquals(StepState.ACTIVE, s.link)
    }

    @Test fun `link open and adapter initializing are Bluetooth connected only`() {
        listOf(ConnectPhase.LINK_OPEN, ConnectPhase.INITIALIZING_ADAPTER).forEach { phase ->
            val s = map(ObdConnectionState.Connecting(bt, "OBDII", phase))
            assertEquals(DiyConnectionStage.BLUETOOTH_CONNECTED, s.stage)
            assertTrue(s.inProgress)
            assertEquals(StepState.DONE, s.link)
            assertEquals(StepState.ACTIVE, s.adapterStep)
            assertEquals(StepState.PENDING, s.ecuStep)
        }
    }

    @Test fun `waiting for the vehicle is adapter ready and in progress`() {
        val s = map(ObdConnectionState.Connecting(bt, "OBDII", ConnectPhase.WAITING_FOR_VEHICLE))
        assertEquals(DiyConnectionStage.ADAPTER_READY, s.stage)
        assertTrue(s.inProgress)
        assertEquals(StepState.DONE, s.adapterStep)
        assertEquals(StepState.ACTIVE, s.ecuStep)
    }

    @Test fun `connected with no vehicle protocol is adapter ready with an ignition hint`() {
        val s = map(connected(protocol = null))
        assertEquals(DiyConnectionStage.ADAPTER_READY, s.stage)
        assertFalse(s.inProgress)
        assertEquals(StepState.PENDING, s.ecuStep)
        assertTrue(s.linkLabel.contains("ignition", ignoreCase = true))
        assertNull(s.protocol)
    }

    @Test fun `connect phase ECU responding is ECU responding while finishing setup`() {
        val s = map(ObdConnectionState.Connecting(bt, "OBDII", ConnectPhase.ECU_RESPONDING))
        assertEquals(DiyConnectionStage.ECU_RESPONDING, s.stage)
        assertTrue(s.inProgress)
    }

    @Test fun `connected with a protocol and healthy link is ECU responding`() {
        val s = map(connected())
        assertEquals(DiyConnectionStage.ECU_RESPONDING, s.stage)
        assertFalse(s.inProgress)
        assertEquals(listOf(StepState.DONE, StepState.DONE, StepState.DONE), listOf(s.link, s.adapterStep, s.ecuStep))
        assertEquals("ISO 15765-4 CAN", s.protocol)
        assertEquals("ELM327 v1.5", s.adapter)
    }

    @Test fun `stale readings with no live ones make the stage stale and carry the newest time`() {
        val older = SensorValueState.Stale(1.0, now.minusSeconds(60))
        val newer = SensorValueState.Stale(2.0, now.minusSeconds(30))
        val s = map(connected(), listOf(older, newer, SensorValueState.Unsupported))
        assertEquals(DiyConnectionStage.STALE, s.stage)
        assertEquals(now.minusSeconds(30), s.lastDataAt)
    }

    @Test fun `a live reading keeps the stage ECU responding even if another is stale`() {
        val s = map(connected(), listOf(SensorValueState.Stale(1.0, now.minusSeconds(60)), SensorValueState.Live(2.0, now)))
        assertEquals(DiyConnectionStage.ECU_RESPONDING, s.stage)
        assertNull(s.lastDataAt)
    }

    @Test fun `degraded is stale and recovering is stale in progress`() {
        val degraded = map(connected(health = LinkHealth.DEGRADED))
        assertEquals(DiyConnectionStage.STALE, degraded.stage)
        assertFalse(degraded.inProgress)
        assertEquals(StepState.FAILED, degraded.ecuStep)
        val recovering = map(connected(health = LinkHealth.RECOVERING))
        assertEquals(DiyConnectionStage.STALE, recovering.stage)
        assertTrue(recovering.inProgress)
        assertEquals(StepState.ACTIVE, recovering.ecuStep)
    }

    @Test fun `reconnecting is disconnected in progress`() {
        val s = map(ObdConnectionState.Reconnecting("OBDII", 1, 3))
        assertEquals(DiyConnectionStage.DISCONNECTED, s.stage)
        assertTrue(s.inProgress)
    }

    @Test fun `failed and unavailable are errors and failed carries its plain message`() {
        val failed = map(ObdConnectionState.Failed("Your adapter didn't answer in time.", ConnectFailure.TIMEOUT, "secret detail"))
        assertEquals(DiyConnectionStage.ERROR, failed.stage)
        assertEquals("Your adapter didn't answer in time.", failed.linkLabel)
        assertEquals(StepState.FAILED, failed.link)
        assertEquals(DiyConnectionStage.ERROR, map(ObdConnectionState.Unavailable("no bluetooth")).stage)
    }

    @Test fun `sample readings never raise the stage`() {
        val sample = listOf(SensorValueState.Sample(92.0, now), SensorValueState.Sample(14.1, now))
        listOf(
            ObdConnectionState.Disconnected,
            ObdConnectionState.Connecting(bt, "OBDII", ConnectPhase.LINK_OPEN),
            connected(protocol = null),
        ).forEach { state ->
            assertEquals(map(state).stage, map(state, sample).stage)
            assertTrue(map(state, sample).stage != DiyConnectionStage.ECU_RESPONDING)
        }
    }

    @Test fun `ECU responding is only ever reported with a vehicle answer`() {
        val states = buildList<ObdConnectionState> {
            add(ObdConnectionState.Disconnected)
            ConnectPhase.entries.forEach { add(ObdConnectionState.Connecting(bt, "OBDII", it)) }
            listOf(null, "ISO 15765-4 CAN").forEach { p -> LinkHealth.entries.forEach { h -> add(connected(p, h)) } }
            add(ObdConnectionState.Reconnecting("OBDII", 2, 3))
            add(ObdConnectionState.Unavailable("x"))
            ConnectFailure.entries.forEach { add(ObdConnectionState.Failed("m", it)) }
        }
        states.forEach { state ->
            val stage = map(state).stage
            if (stage == DiyConnectionStage.ECU_RESPONDING) {
                val ok = (state is ObdConnectionState.Connected && state.vehicleProtocol != null) ||
                    (state is ObdConnectionState.Connecting && state.phase == ConnectPhase.ECU_RESPONDING)
                assertTrue("$state must not read as ECU responding", ok)
            }
        }
    }

    @Test fun `Bluetooth alone shows the link done and the ECU pending`() {
        val s = map(ObdConnectionState.Connecting(bt, "OBDII", ConnectPhase.LINK_OPEN))
        assertEquals(StepState.DONE, s.link)
        assertEquals(StepState.PENDING, s.ecuStep)
    }

    @Test fun `staleness follows the sensor mapper at 11 seconds and live at 9 seconds`() {
        val state = connected()
        fun stage(ageSeconds: Long): DiyConnectionStage {
            val at = now.minus(Duration.ofSeconds(ageSeconds))
            val states = LiveDataStateMapper.map(
                setOf(0x0C), LivePollSnapshot(mapOf(0x0C to PidSample.Ok(800.0, at))), state, now, demo = false,
            )
            return map(state, states.values).stage
        }
        assertEquals(DiyConnectionStage.ECU_RESPONDING, stage(9))
        assertEquals(DiyConnectionStage.STALE, stage(11))
    }

    @Test fun `the adapter text is never an address`() {
        val mac = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")
        val s = map(connected(name = "AA:BB:CC:DD:EE:FF", version = null))
        assertNull(s.adapter)
        listOf(connected(), connected(name = "OBDII", version = null), connected(protocol = null)).forEach {
            map(it).adapter?.let { a -> assertFalse(mac.containsMatchIn(a)) }
        }
        assertNotNull(map(connected(name = "OBDII", version = null)).adapter)
    }
}
