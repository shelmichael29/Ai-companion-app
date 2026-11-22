package com.aicarcompanion.app.diagnostics

import com.aicarcompanion.app.assistant.conversation.CodeReadResult
import com.aicarcompanion.app.assistant.conversation.LiveValueResult
import com.aicarcompanion.app.data.memory.InMemoryHistoryRepository
import com.aicarcompanion.app.diagnostics.obd.AdapterDevice
import com.aicarcompanion.app.diagnostics.obd.AdapterRadio
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdManager
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DtcStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

class ObdManagerTest {

    private val panlong = AdapterDevice("OBDII", "00:1D:A5:00:00:01", AdapterRadio.CLASSIC)
    private val history = InMemoryHistoryRepository()

    /** The manager runs in backgroundScope, which advanceUntilIdle() deliberately skips. */
    private fun TestScope.settle(millis: Long = 0) {
        if (millis > 0) advanceTimeBy(millis)
        runCurrent()
    }

    private fun TestScope.manager(vararg adapters: FakeElmAdapter): ObdManager {
        val queue = ArrayDeque(adapters.toList())
        return ObdManager(
            backgroundScope,
            { queue.removeFirstOrNull() ?: throw IOException("adapter not found") },
            history,
            now = { Instant.parse("2026-11-14T12:00:00Z") },
            reconnectDelaysOverride = listOf(1_000, 1_000),
        )
    }

    @Test fun `connects, initializes and identifies the vehicle protocol`() = runTest {
        val adapter = FakeElmAdapter()
        val m = manager(adapter)
        m.connect(panlong); settle()
        val s = m.state.value as ObdConnectionState.Connected
        assertEquals("OBDII", s.adapterName)
        assertEquals("ISO 15765-4 CAN (11-bit, 500 kbaud)", s.vehicleProtocol)
        assertEquals("ELM327 v1.5", s.adapterVersion)
        assertTrue(ObdPid.ENGINE_RPM.pid in m.supportedPids.value)
        assertTrue(0x42 in m.supportedPids.value)
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS1", "ATH0", "ATAT1", "ATSP0", "0100", "0120", "0140", "ATDPN"), adapter.written)
    }

    @Test fun `ignition off - adapter connected but vehicle not responding`() = runTest {
        val m = manager(FakeElmAdapter(vehicleOn = false))
        m.connect(panlong); settle()
        val s = m.state.value as ObdConnectionState.Connected
        assertEquals(null, s.vehicleProtocol)
        val r = m.readLiveValue(ObdPid.COOLANT_TEMP)
        assertTrue(r is LiveValueResult.NoResponse)
    }

    @Test fun `ignition turned on later is picked up by retry`() = runTest {
        val adapter = FakeElmAdapter(vehicleOn = false)
        val m = manager(adapter)
        m.connect(panlong); settle()
        adapter.vehicleOn = true
        m.retryVehicle(); settle()
        assertTrue((m.state.value as ObdConnectionState.Connected).vehicleProtocol != null)
    }

    @Test fun `reads live values from the vehicle`() = runTest {
        val m = manager(FakeElmAdapter())
        m.connect(panlong); settle()
        val coolant = m.readLiveValue(ObdPid.COOLANT_TEMP) as LiveValueResult.Value
        assertEquals(90.0, coolant.reading.value, 0.0)
        assertEquals(DataOrigin.OBD_BLUETOOTH, coolant.reading.origin)
        assertEquals(800.0, (m.readLiveValue(ObdPid.ENGINE_RPM) as LiveValueResult.Value).reading.value, 0.0)
    }

    @Test fun `unsupported pid is not requested`() = runTest {
        val adapter = FakeElmAdapter(mapOf("0100" to "41 00 00 08 00 00")) // only 0x0D
        val m = manager(adapter)
        m.connect(panlong); settle()
        assertEquals(LiveValueResult.NotSupported, m.readLiveValue(ObdPid.FUEL_LEVEL))
        assertFalse("012F" in adapter.written)
    }

    @Test fun `voltage falls back to adapter measurement when pid 42 unsupported`() = runTest {
        val m = manager(FakeElmAdapter(mapOf("0100" to "41 00 00 08 00 00")))
        m.connect(panlong); settle()
        val v = m.readLiveValue(ObdPid.CONTROL_MODULE_VOLTAGE) as LiveValueResult.Value
        assertEquals(12.4, v.reading.value, 0.0)
        assertTrue(v.reading.name.contains("adapter"))
    }

    @Test fun `not connected never produces values`() = runTest {
        val m = manager()
        assertEquals(LiveValueResult.NotConnected, m.readLiveValue(ObdPid.COOLANT_TEMP))
        assertEquals(CodeReadResult.NotConnected, m.readTroubleCodes("bmw"))
    }

    @Test fun `reads stored and pending codes and saves them per vehicle`() = runTest {
        val adapter = FakeElmAdapter(mapOf("07" to "47 01 04 20"))
        val m = manager(adapter)
        m.connect(panlong); settle()
        val read = (m.readTroubleCodes("bmw") as CodeReadResult.Read).read
        assertEquals(listOf("P0171" to DtcStatus.STORED, "P0420" to DtcStatus.PENDING), read.codes.map { it.code to it.status })
        assertEquals(read, history.latestTroubleCodeRead("bmw").first())
        assertEquals(null, history.latestTroubleCodeRead("other-car").first())
        assertFalse("04" in adapter.written)
    }

    @Test fun `LE-only device is rejected with an explanation`() = runTest {
        val m = manager(FakeElmAdapter())
        m.connect(panlong.copy(radio = AdapterRadio.LE_ONLY)); settle()
        assertTrue((m.state.value as ObdConnectionState.Failed).message.contains("Low Energy"))
    }

    @Test fun `unreachable adapter fails cleanly`() = runTest {
        val m = manager()
        m.connect(panlong); settle()
        assertTrue(m.state.value is ObdConnectionState.Failed)
    }

    @Test fun `lost link reconnects automatically`() = runTest {
        val first = FakeElmAdapter()
        val second = FakeElmAdapter()
        val m = manager(first, second)
        m.connect(panlong); settle()
        first.failAfterWrites = first.written.size
        m.readLiveValue(ObdPid.ENGINE_RPM)
        assertTrue(first.closed)
        assertTrue(m.state.value is ObdConnectionState.Reconnecting)
        settle(1_500)
        assertTrue(m.state.value is ObdConnectionState.Connected)
    }

    @Test fun `gives up after reconnect attempts`() = runTest {
        val first = FakeElmAdapter()
        val m = manager(first)
        m.connect(panlong); settle()
        first.failAfterWrites = first.written.size
        m.readLiveValue(ObdPid.ENGINE_RPM)
        settle(10_000)
        assertTrue((m.state.value as ObdConnectionState.Failed).message.contains("Lost connection"))
    }

    @Test fun `user disconnect does not reconnect`() = runTest {
        val adapter = FakeElmAdapter()
        val m = manager(adapter, FakeElmAdapter())
        m.connect(panlong); settle()
        m.disconnect(); settle()
        assertEquals(ObdConnectionState.Disconnected, m.state.value)
        assertTrue(adapter.closed)
    }

    @Test fun `a failed refresh removes the previous sensor value`() = runTest {
        val adapter = FakeElmAdapter()
        val m = manager(adapter)
        m.connect(panlong); settle()
        val snapshots = mutableListOf<List<com.aicarcompanion.app.domain.model.SensorReading>>()
        val collector = backgroundScope.launch { m.liveReadings.collect { snapshots += it } }
        settle()
        assertTrue(snapshots.any { snapshot -> snapshot.any { it.pid == "0105" } })
        adapter.responses["0105"] = "NO DATA"
        settle(1_500)
        assertTrue("A failed coolant refresh must not display the previous reading",
            snapshots.last().none { it.pid == "0105" })
        collector.cancel()
    }

    @Test fun `live readings poll supported pids while collected`() = runTest {
        val m = manager(FakeElmAdapter())
        m.connect(panlong); settle()
        val readings = m.liveReadings.first { it.isNotEmpty() }
        val names = readings.map { it.pid }
        assertTrue(names.containsAll(listOf("010C", "010D", "0105", "0142")))
        assertTrue(readings.all { it.origin == DataOrigin.OBD_BLUETOOTH })
    }
}
