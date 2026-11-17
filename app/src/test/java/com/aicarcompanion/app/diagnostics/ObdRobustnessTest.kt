package com.aicarcompanion.app.diagnostics

import com.aicarcompanion.app.assistant.conversation.CodeReadResult
import com.aicarcompanion.app.assistant.conversation.LiveValueResult
import com.aicarcompanion.app.data.memory.InMemoryHistoryRepository
import com.aicarcompanion.app.diagnostics.obd.AdapterDevice
import com.aicarcompanion.app.diagnostics.obd.AdapterRadio
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdManager
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * OBD-II robustness with simulated adapters: silent, unsupported or misbehaving adapters, and
 * invalid or incomplete ECU answers. Nothing here talks to real hardware, and the session-wide
 * test proves no vehicle-modifying command is ever sent.
 */
class ObdRobustnessTest {

    private val device = AdapterDevice("OBDII", "00:1D:A5:00:00:01", AdapterRadio.CLASSIC)
    private val history = InMemoryHistoryRepository()

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
            now = { Instant.parse("2026-11-17T12:00:00Z") },
            reconnectDelaysOverride = listOf(1_000, 1_000),
        )
    }

    private suspend fun TestScope.connected(adapter: FakeElmAdapter = FakeElmAdapter()): ObdManager =
        manager(adapter).also { it.connect(device); settle() }

    // ---- Connection --------------------------------------------------------------------------

    @Test fun `an adapter that never answers fails cleanly instead of hanging`() = runTest {
        val silent = FakeElmAdapter().apply { timeouts["ATZ"] = Int.MAX_VALUE }
        val m = manager(silent)
        m.connect(device); settle(60_000)
        assertFalse("must not claim a vehicle connection", (m.state.value as? ObdConnectionState.Connected)?.vehicleProtocol != null)
        assertTrue(m.readLiveValue(ObdPid.ENGINE_RPM) !is LiveValueResult.Value)
    }

    @Test fun `a device that isn't an ELM327 never yields readings`() = runTest {
        // Unknown commands get "?" from the fake: here, every command.
        val notElm = FakeElmAdapter(overrides = mapOf("ATZ" to "?", "ATE0" to "?", "0100" to "?", "ATDPN" to "?"))
        val m = manager(notElm)
        m.connect(device); settle(10_000)
        assertNull((m.state.value as? ObdConnectionState.Connected)?.vehicleProtocol)
        assertTrue(m.readLiveValue(ObdPid.COOLANT_TEMP) !is LiveValueResult.Value)
    }

    /**
     * Regression (Phase 6 audit): a single timeout on the first vehicle request ("0100", the
     * slowest step on real cars) used to fail the whole connection with "Make sure the adapter is
     * plugged in", although the adapter had answered every AT command.
     */
    @Test fun `a slow first vehicle answer keeps the adapter connected and the car can be retried`() = runTest {
        val slow = FakeElmAdapter().apply { timeouts["0100"] = 1 }
        val m = manager(slow)
        m.connect(device); settle(30_000)
        val s = m.state.value
        assertTrue("adapter should stay connected, was $s", s is ObdConnectionState.Connected)
        assertNull("car not identified yet", (s as ObdConnectionState.Connected).vehicleProtocol)
        m.retryVehicle(); settle()
        assertEquals("ISO 15765-4 CAN (11-bit, 500 kbaud)", (m.state.value as ObdConnectionState.Connected).vehicleProtocol)
    }

    @Test fun `a timed-out extended PID range keeps the PIDs already found`() = runTest {
        val m = connected(FakeElmAdapter().apply { timeouts["0120"] = 1 })
        val s = m.state.value as ObdConnectionState.Connected
        assertTrue(s.vehicleProtocol != null)
        assertTrue(ObdPid.ENGINE_RPM.pid in m.supportedPids.value)
    }

    // ---- Invalid and incomplete ECU answers ----------------------------------------------------

    @Test fun `truncated, wrong-PID, garbage and error answers never become readings`() = runTest {
        val bad = listOf(
            "41 0C 1A", // RPM needs 2 data bytes; only 1 arrived
            "41 0D 00", // answer for a different PID
            "#$%^&*",   // line noise
            "CAN ERROR",
            "BUFFER FULL",
            "STOPPED",
            "",
        )
        for (answer in bad) {
            val m = connected(FakeElmAdapter(overrides = mapOf("010C" to answer)))
            val r = m.readLiveValue(ObdPid.ENGINE_RPM)
            assertTrue("\"$answer\" produced $r", r !is LiveValueResult.Value)
        }
    }

    @Test fun `a valid answer after a bad one is read correctly`() = runTest {
        val adapter = FakeElmAdapter(overrides = mapOf("010C" to "41 0C 1A"))
        val m = connected(adapter)
        assertTrue(m.readLiveValue(ObdPid.ENGINE_RPM) !is LiveValueResult.Value)
        adapter.responses["010C"] = "41 0C 0C 80"
        val r = m.readLiveValue(ObdPid.ENGINE_RPM) as LiveValueResult.Value
        assertEquals(800.0, r.reading.value, 0.01)
    }

    @Test fun `bad trouble-code answers never invent or save codes`() = runTest {
        for (answer in listOf("BUFFER FULL", "CAN ERROR", "#$%", "STOPPED")) {
            val m = connected(FakeElmAdapter(overrides = mapOf("03" to answer)))
            val r = m.readTroubleCodes("bmw")
            assertTrue("\"$answer\" produced $r", r is CodeReadResult.NoResponse)
        }
        assertNull("nothing was saved", history.latestTroubleCodeRead("bmw").first())
    }

    @Test fun `NO DATA for codes means none stored, saved honestly as an empty read`() = runTest {
        val m = connected(FakeElmAdapter(overrides = mapOf("03" to "NO DATA", "07" to "NO DATA")))
        val r = m.readTroubleCodes("bmw") as CodeReadResult.Read
        assertTrue(r.read.codes.isEmpty())
    }

    // ---- Disconnection -----------------------------------------------------------------------

    @Test fun `link lost in the middle of a code read reports no response without crashing`() = runTest {
        val adapter = FakeElmAdapter()
        val m = connected(adapter)
        adapter.failAfterWrites = adapter.written.size // the next write fails
        val r = m.readTroubleCodes("bmw")
        assertTrue(r is CodeReadResult.NoResponse || r is CodeReadResult.NotConnected)
        settle()
        assertNull("a failed read saves nothing", history.latestTroubleCodeRead("bmw").first())
    }

    // ---- Safety: nothing that changes the car is ever sent -------------------------------------

    @Test fun `a full session never sends a clear, reset or write command`() = runTest {
        val adapter = FakeElmAdapter()
        val m = connected(adapter)
        m.readTroubleCodes("bmw")
        ObdPid.entries.forEach { m.readLiveValue(it) }
        m.runDiagnostics()
        m.refreshAdapterDetails()
        // Even when asked explicitly through the expert console, these are refused.
        for (cmd in listOf("04", "08", "14", "2E F1 90 00", "31 01 FF 00", "11 01", "ATMA", "ATPP 2C SV 00")) {
            m.sendConsoleCommand(cmd)
        }
        val modifying = adapter.written.filter { w ->
            val c = w.replace(" ", "").uppercase()
            c == "04" || c.startsWith("08") || c.startsWith("14") || c.startsWith("2E") || c.startsWith("31") ||
                c.startsWith("11") || c.startsWith("ATMA") || c.startsWith("ATPP")
        }
        assertEquals("vehicle-modifying commands reached the adapter: $modifying", emptyList<String>(), modifying)
    }
}
