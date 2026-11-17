package com.aicarcompanion.app.diagnostics

import com.aicarcompanion.app.assistant.conversation.LiveValueResult
import com.aicarcompanion.app.connectivity.ConnectionLog
import com.aicarcompanion.app.data.memory.InMemoryHistoryRepository
import com.aicarcompanion.app.diagnostics.obd.ElmLink
import com.aicarcompanion.app.diagnostics.obd.AdapterDevice
import com.aicarcompanion.app.diagnostics.obd.AdapterRadio
import com.aicarcompanion.app.diagnostics.obd.ConnectPhase
import com.aicarcompanion.app.diagnostics.obd.InMemoryProtocolMemory
import com.aicarcompanion.app.diagnostics.obd.LinkHealth
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionConfig
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdManager
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import com.aicarcompanion.app.diagnostics.obd.ObdProtocol
import com.aicarcompanion.app.diagnostics.obd.ObdTransport
import com.aicarcompanion.app.diagnostics.obd.ReadOnlyCommandPolicy
import com.aicarcompanion.app.diagnostics.obd.VehicleLinkStage
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.Vehicle
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * Phase 7: vehicle connection stages, protocol fallback and bounded recovery, with simulated
 * adapters only. Models the 2011 BMW 528i report: "the scanner connects, but the car doesn't respond".
 */
class ObdConnectionRecoveryTest {

    /**
     * An ELM327-like adapter whose vehicle answers only on [carProtocols]. [autoSearchWorks] = false
     * models clones whose automatic protocol search (ATSP0) fails on some CAN cars.
     */
    private class ProtocolAdapter(
        val carProtocols: Set<String> = setOf("6"),
        val autoSearchWorks: Boolean = true,
        val acceptsProtocols: Set<String> = setOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "A", "B", "C"),
    ) : ElmLink {
        val written = mutableListOf<String>()
        var carAwake = true
        var linkUp = true
        private var current = "0"
        private var pending: String? = null
        override suspend fun write(text: String) {
            if (!linkUp) throw IOException("link lost")
            val cmd = text.trim()
            written += cmd
            pending = when {
                cmd == "ATZ" -> "ELM327 v1.5"
                cmd.startsWith("ATSP") -> cmd.removePrefix("ATSP").let { p -> if (p in acceptsProtocols) { current = p; "OK" } else "?" }
                cmd.startsWith("AT") && cmd != "ATDPN" -> "OK"
                cmd == "ATDPN" -> if (current == "0") "A6" else current
                !carAwake || !answers() -> if (current == "0") "SEARCHING...\rUNABLE TO CONNECT" else "NO DATA"
                cmd == "0100" -> "41 00 BE 3F A8 13"
                cmd == "0120" || cmd == "0140" -> "41 ${cmd.takeLast(2)} 00 00 00 00"
                cmd == "010C" -> "41 0C 0C 80"
                cmd == "0105" -> "41 05 82"
                cmd == "03" -> "43 00"
                cmd == "07" -> "47 00"
                else -> "NO DATA"
            }
        }
        private fun answers() = if (current == "0") autoSearchWorks && "6" in carProtocols else current in carProtocols
        override suspend fun readUntilPrompt(timeoutMillis: Long): String {
            if (!linkUp) throw IOException("link lost")
            val r = pending ?: throw IOException("nothing pending"); pending = null; return "$r\r\r>"
        }
        override fun close() { linkUp = false }
    }

    private val bmw = Vehicle(
        id = "bmw-528i", year = 2011, make = "BMW", model = "528i", trim = "F10",
        engine = EngineSpec(3.0, 6, EngineLayout.INLINE, engineCode = "N52B30"), odometerMiles = 98_000,
    )
    private val device = AdapterDevice("OBDII", "00:1D:A5:00:00:01", AdapterRadio.CLASSIC)
    private val memory = InMemoryProtocolMemory()
    private val log = ConnectionLog(now = { Instant.parse("2026-11-17T12:00:00Z") })

    private fun TestScope.settle(ms: Long = 0) {
        if (ms > 0) advanceTimeBy(ms)
        runCurrent()
    }

    private fun TestScope.manager(vararg links: ElmLink, config: ObdConnectionConfig = ObdConnectionConfig.RECOMMENDED): ObdManager {
        val queue = ArrayDeque(links.toList())
        return ObdManager(
            backgroundScope, { queue.removeFirstOrNull() ?: throw IOException("adapter not found") }, InMemoryHistoryRepository(),
            now = { Instant.parse("2026-11-17T12:00:00Z") }, reconnectDelaysOverride = listOf(1_000), config = { config },
            activeVehicle = { bmw }, protocolMemory = memory, log = log, recheckDelaysOverride = listOf(1_000, 2_000),
        )
    }

    private fun stage(m: ObdManager) = VehicleLinkStage.of(m.state.value)

    // ---- The reported BMW failure ------------------------------------------------------------

    /** Regression: the adapter's automatic search failed and Penelope never tried CAN 11/500 directly. */
    @Test fun `when automatic search fails, CAN 11-bit 500k is tried and the car connects`() = runTest {
        val adapter = ProtocolAdapter(carProtocols = setOf("6"), autoSearchWorks = false)
        val m = manager(adapter)
        m.connect(device); settle()
        assertEquals(VehicleLinkStage.VEHICLE_CONNECTED, stage(m))
        assertTrue("ATSP6 was tried after ATSP0", adapter.written.indexOf("ATSP6") > adapter.written.indexOf("ATSP0"))
        assertEquals(ObdProtocol.CAN_11_500, memory.lastWorking(bmw.id))
        assertTrue(m.readLiveValue(ObdPid.ENGINE_RPM) is LiveValueResult.Value)
    }

    @Test fun `the protocol that worked is tried first next time`() = runTest {
        memory.remember(bmw.id, ObdProtocol.CAN_11_500)
        val adapter = ProtocolAdapter(carProtocols = setOf("6"), autoSearchWorks = false)
        val m = manager(adapter)
        m.connect(device); settle()
        assertEquals(VehicleLinkStage.VEHICLE_CONNECTED, stage(m))
        val probes = adapter.written.filter { it == "0100" }
        assertEquals("connected on the first vehicle request", 1, probes.size)
    }

    @Test fun `a remembered protocol that stopped working falls back and is updated`() = runTest {
        memory.remember(bmw.id, ObdProtocol.CAN_29_500)
        val m = manager(ProtocolAdapter(carProtocols = setOf("6"), autoSearchWorks = true))
        m.connect(device); settle()
        assertEquals(VehicleLinkStage.VEHICLE_CONNECTED, stage(m))
        assertEquals(ObdProtocol.CAN_11_500, memory.lastWorking(bmw.id))
    }

    @Test fun `adapter connected but ECU silent is never shown as a vehicle connection`() = runTest {
        val adapter = ProtocolAdapter(carProtocols = emptySet())
        val m = manager(adapter)
        m.connect(device); settle()
        val s = m.state.value as ObdConnectionState.Connected
        assertNull(s.vehicleProtocol)
        assertEquals(VehicleLinkStage.VEHICLE_PENDING, stage(m))
        assertFalse(stage(m).vehicleReady)
        assertEquals(
            "Your scanner is connected, but your vehicle hasn't responded. I'm checking the connection.",
            stage(m).everydayMessage,
        )
        // Bounded: auto search plus the model-year fallbacks, then back to automatic.
        assertEquals(listOf("ATSP0", "ATSP6", "ATSP7", "ATSP0"), adapter.written.filter { it.startsWith("ATSP") })
        assertEquals(3, adapter.written.count { it == "0100" })
    }

    @Test fun `protocols the adapter rejects are skipped and logged`() = runTest {
        val m = manager(ProtocolAdapter(carProtocols = setOf("7"), autoSearchWorks = false, acceptsProtocols = setOf("0", "7")))
        m.connect(device); settle()
        assertEquals(VehicleLinkStage.VEHICLE_CONNECTED, stage(m))
        assertTrue(log.export().contains("Protocol not supported by the adapter"))
    }

    @Test fun `a protocol chosen in Advanced settings is respected without fallbacks`() = runTest {
        val manual = ObdConnectionConfig.RECOMMENDED.copy(protocol = ObdProtocol.CAN_29_500, protocolFallbackToAuto = false)
        val adapter = ProtocolAdapter(carProtocols = setOf("6"))
        val m = manager(adapter, config = manual)
        m.connect(device); settle()
        assertEquals(VehicleLinkStage.VEHICLE_PENDING, stage(m))
        assertFalse("no automatic protocol changes", adapter.written.contains("ATSP6"))
    }

    @Test fun `connecting reports each stage before the vehicle is connected`() = runTest {
        val m = manager(ProtocolAdapter())
        val stages = mutableListOf<VehicleLinkStage>()
        // An unconfined collector sees every update, even ones made without suspending in between.
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { m.state.collect { stages += VehicleLinkStage.of(it) } }
        m.connect(device); settle()
        job.cancel()
        val order = listOf(VehicleLinkStage.ADAPTER_INITIALIZING, VehicleLinkStage.VEHICLE_PENDING, VehicleLinkStage.ECU_RESPONDING, VehicleLinkStage.VEHICLE_CONNECTED)
        assertEquals(order, stages.filter { it in order }.distinct())
        assertEquals(VehicleLinkStage.VEHICLE_CONNECTED, stages.last())
    }

    // ---- Degraded connection and bounded recovery ----------------------------------------------

    @Test fun `a car that stops answering is marked degraded and recovers when it answers again`() = runTest {
        val adapter = ProtocolAdapter()
        val m = manager(adapter)
        m.connect(device); settle()
        adapter.carAwake = false
        repeat(3) { m.readLiveValue(ObdPid.ENGINE_RPM) }
        settle()
        val s = m.state.value as ObdConnectionState.Connected
        assertTrue("degraded, not still 'connected'", s.health != LinkHealth.OK)
        assertFalse(stage(m).vehicleReady)
        adapter.carAwake = true
        settle(1_000)
        assertEquals(VehicleLinkStage.VEHICLE_CONNECTED, stage(m))
        assertTrue(log.export().contains("Connection restored"))
    }

    @Test fun `recovery gives up after a bounded number of attempts and then sends nothing`() = runTest {
        val adapter = ProtocolAdapter()
        val m = manager(adapter)
        m.connect(device); settle()
        adapter.carAwake = false
        repeat(3) { m.readLiveValue(ObdPid.ENGINE_RPM) }
        settle(10_000)
        assertEquals(VehicleLinkStage.VEHICLE_PENDING, stage(m))
        val sent = adapter.written.size
        settle(600_000) // ten minutes later
        assertEquals("no endless reconnection loop", sent, adapter.written.size)
        assertTrue(adapter.written.contains("ATZ"))
        assertTrue(log.export().contains("Vehicle not responding after recovery"))
    }

    @Test fun `the user disconnecting stops recovery immediately`() = runTest {
        val adapter = ProtocolAdapter()
        val m = manager(adapter)
        m.connect(device); settle()
        adapter.carAwake = false
        repeat(3) { m.readLiveValue(ObdPid.ENGINE_RPM) }
        settle()
        m.disconnect()
        val sent = adapter.written.size
        settle(60_000)
        assertEquals(ObdConnectionState.Disconnected, m.state.value)
        assertEquals(sent, adapter.written.size)
    }

    @Test fun `losing the Bluetooth link during recovery switches to reconnecting and restores the session`() = runTest {
        val first = ProtocolAdapter()
        val second = ProtocolAdapter()
        val m = manager(first, second)
        m.connect(device); settle()
        first.carAwake = false
        repeat(3) { m.readLiveValue(ObdPid.ENGINE_RPM) }
        settle()
        first.linkUp = false
        settle(1_000)
        settle(1_000)
        assertEquals(VehicleLinkStage.VEHICLE_CONNECTED, stage(m))
        assertTrue(second.written.contains("0100"))
    }

    @Test fun `automatic recovery only ever sends read-only commands`() = runTest {
        val adapter = ProtocolAdapter()
        val m = manager(adapter)
        m.connect(device); settle()
        adapter.carAwake = false
        repeat(3) { m.readLiveValue(ObdPid.ENGINE_RPM) }
        settle(20_000)
        adapter.written.forEach { assertTrue("$it must be read-only", ReadOnlyCommandPolicy.isAllowed(it)) }
        assertFalse(adapter.written.any { it == "04" || it.startsWith("14") || it.startsWith("2E") })
    }

    // ---- Transport separation --------------------------------------------------------------

    @Test fun `ELM327 recovery is only for ELM adapters and ENET is not a transport in this app`() {
        // BMW ENET (HSFZ over Ethernet) uses different procedures and lives on its own branches.
        assertFalse(ObdTransport.entries.any { it.name.contains("ETHERNET") || it.name.contains("ENET") })
        assertNotNull(ObdTransport.BLUETOOTH_CLASSIC)
    }

    @Test fun `every stage has plain words for Everyday drivers and only one means ready`() {
        VehicleLinkStage.entries.forEach { assertTrue(it.everydayMessage.isNotBlank()) }
        assertEquals(listOf(VehicleLinkStage.VEHICLE_CONNECTED), VehicleLinkStage.entries.filter { it.vehicleReady })
        assertEquals(VehicleLinkStage.ADAPTER_CONNECTED, VehicleLinkStage.of(ObdConnectionState.Connecting(ObdTransport.BLUETOOTH_CLASSIC, "OBDII", ConnectPhase.LINK_OPEN)))
        assertEquals(VehicleLinkStage.PAIRING_REQUIRED, VehicleLinkStage.of(ObdConnectionState.Disconnected, pairedAdapterAvailable = false))
        assertEquals(VehicleLinkStage.RECOVERING, VehicleLinkStage.of(ObdConnectionState.Reconnecting("OBDII", 1, 3)))
        assertEquals(VehicleLinkStage.FAILED, VehicleLinkStage.of(ObdConnectionState.Failed("x")))
    }
}
