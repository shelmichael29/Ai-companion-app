package com.aicarcompanion.app.diagnostics

import com.aicarcompanion.app.assistant.conversation.CodeReadResult
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.memory.InMemoryHistoryRepository
import com.aicarcompanion.app.diagnostics.obd.AdapterDevice
import com.aicarcompanion.app.diagnostics.obd.AdapterRadio
import com.aicarcompanion.app.diagnostics.obd.ClearCodesRefusal
import com.aicarcompanion.app.diagnostics.obd.ClearCodesResult
import com.aicarcompanion.app.diagnostics.obd.ClearCodesTokenResult
import com.aicarcompanion.app.diagnostics.obd.ConsoleResult
import com.aicarcompanion.app.diagnostics.obd.Elm327ResponseParser
import com.aicarcompanion.app.diagnostics.obd.Elm327Session
import com.aicarcompanion.app.diagnostics.obd.GateDecision
import com.aicarcompanion.app.diagnostics.obd.GateRefusal
import com.aicarcompanion.app.diagnostics.obd.ObdManager
import com.aicarcompanion.app.diagnostics.obd.ReadOnlyCommandPolicy
import com.aicarcompanion.app.diagnostics.obd.VehicleWriteGate
import com.aicarcompanion.app.domain.model.ObservationKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant

/** The first vehicle-modifying command (Mode 04). Fake adapters only; never a real one. */
class ClearCodesSafetyTest {

    private val device = AdapterDevice("OBDII", "00:1D:A5:00:00:01", AdapterRadio.CLASSIC)
    private val history = InMemoryHistoryRepository()
    private var clock = Instant.parse("2026-11-21T12:00:00Z")
    private var level = ExplanationLevel.DIY
    private val vehicle = "bmw"

    /** Engine off, ignition on: rpm 0. A clear is only possible in this state. */
    private fun engineOff(extra: Map<String, String> = emptyMap()) =
        FakeElmAdapter(mapOf("010C" to "41 0C 00 00", "04" to "44") + extra)

    private fun TestScope.manager(vararg adapters: FakeElmAdapter): ObdManager {
        val queue = ArrayDeque(adapters.toList())
        return ObdManager(
            backgroundScope, { queue.removeFirstOrNull() ?: throw IOException("adapter not found") }, history,
            now = { clock }, reconnectDelaysOverride = listOf(1_000),
            clearCodesAllowed = { level != ExplanationLevel.EVERYDAY },
        )
    }

    private suspend fun TestScope.connectedAndRead(adapter: FakeElmAdapter = engineOff()): ObdManager {
        val m = manager(adapter)
        m.connect(device); runCurrent()
        assertTrue(m.readTroubleCodes(vehicle) is CodeReadResult.Read)
        return m
    }

    private fun ObdManager.token(id: String = vehicle) = (issueClearCodesToken(id) as ClearCodesTokenResult.Issued).token

    // ---- Read-only paths are unchanged --------------------------------------------------------

    @Test fun `policy and console still reject mode 04`() = runTest {
        listOf("04", " 04 ", "0400", "14", "1100", "2E0000").forEach {
            assertFalse(it, ReadOnlyCommandPolicy.isAllowed(it))
            assertFalse(it, ReadOnlyCommandPolicy.isConsoleAllowed(it))
        }
        val adapter = engineOff()
        val m = manager(adapter)
        m.connect(device); runCurrent()
        assertTrue(m.sendConsoleCommand("04") is ConsoleResult.Blocked)
        assertFalse("04" in adapter.written)
    }

    @Test fun `the normal session path cannot send 04`() = runTest {
        val adapter = engineOff()
        val failure = runCatching { Elm327Session(adapter).execute("04") }.exceptionOrNull()
        assertTrue(failure is com.aicarcompanion.app.diagnostics.obd.BlockedCommandException)
        assertTrue(adapter.written.isEmpty())
    }

    @Test fun `only the write gate mints permits and only the manager sends clears`() {
        val root = File("src/main/java/com/aicarcompanion/app")
        assertTrue(root.isDirectory)
        val sources = root.walkTopDown().filter { it.extension == "kt" }.toList()
        fun users(needle: String) = sources.filter { needle in it.readText() }.map { it.name }.toSet()
        assertEquals(setOf("VehicleWriteGate.kt"), users("ClearCodesPermit("))
        assertEquals(setOf("Elm327Session.kt", "ObdManager.kt"), users("sendClearCodes("))
        assertEquals(setOf("Elm327Session.kt"), users("\"04\\r\""))
    }

    // ---- The gate -----------------------------------------------------------------------------

    @Test fun `gate grants a valid token once`() {
        val gate = VehicleWriteGate({ clock })
        val t = gate.issue("bmw", "read1")
        assertTrue(gate.redeem(t, "bmw", "read1") is GateDecision.Granted)
        assertEquals(GateDecision.Refused(GateRefusal.NO_TOKEN), gate.redeem(t, "bmw", "read1")) // replay
    }

    @Test fun `gate refuses without a token`() {
        val gate = VehicleWriteGate({ clock })
        assertEquals(GateDecision.Refused(GateRefusal.NO_TOKEN), gate.redeem(null, "bmw", "read1"))
        val stale = gate.issue("bmw", "read1")
        gate.revoke()
        assertEquals(GateDecision.Refused(GateRefusal.NO_TOKEN), gate.redeem(stale, "bmw", "read1"))
        val forged = VehicleWriteGate({ clock }).issue("bmw", "read1") // from another gate
        gate.issue("bmw", "read1")
        assertEquals(GateDecision.Refused(GateRefusal.NO_TOKEN), gate.redeem(forged, "bmw", "read1"))
    }

    @Test fun `gate refuses an expired token`() {
        val gate = VehicleWriteGate({ clock }, Duration.ofSeconds(60))
        val t = gate.issue("bmw", "read1")
        clock = clock.plusSeconds(60)
        assertEquals(GateDecision.Refused(GateRefusal.EXPIRED), gate.redeem(t, "bmw", "read1"))
    }

    @Test fun `gate accepts a token just before it expires`() {
        val gate = VehicleWriteGate({ clock }, Duration.ofSeconds(60))
        val t = gate.issue("bmw", "read1")
        clock = clock.plusSeconds(59)
        assertTrue(gate.redeem(t, "bmw", "read1") is GateDecision.Granted)
    }

    @Test fun `gate refuses another vehicle or another code read`() {
        val gate = VehicleWriteGate({ clock })
        assertEquals(GateDecision.Refused(GateRefusal.WRONG_VEHICLE), gate.redeem(gate.issue("bmw", "r1"), "audi", "r1"))
        assertEquals(GateDecision.Refused(GateRefusal.WRONG_READ_SESSION), gate.redeem(gate.issue("bmw", "r1"), "bmw", "r2"))
    }

    // ---- Preconditions enforced by the OBD layer ----------------------------------------------

    @Test fun `not connected is refused and sends nothing`() = runTest {
        val m = manager()
        assertEquals(ClearCodesTokenResult.Refused(ClearCodesRefusal.NOT_CONNECTED), m.issueClearCodesToken(vehicle))
    }

    @Test fun `everyday level is refused even with a valid token`() = runTest {
        val adapter = engineOff()
        val m = connectedAndRead(adapter)
        val t = m.token()
        level = ExplanationLevel.EVERYDAY
        assertEquals(ClearCodesResult.Refused(ClearCodesRefusal.LEVEL_NOT_ALLOWED), m.clearCodes(vehicle, t))
        assertEquals(ClearCodesTokenResult.Refused(ClearCodesRefusal.LEVEL_NOT_ALLOWED), m.issueClearCodesToken(vehicle))
        assertFalse("04" in adapter.written)
    }

    @Test fun `advanced level is allowed`() = runTest {
        level = ExplanationLevel.ADVANCED
        val m = connectedAndRead()
        assertTrue(m.clearCodes(vehicle, m.token()) is ClearCodesResult.Cleared)
    }

    @Test fun `codes must be read first in this connection`() = runTest {
        val adapter = engineOff()
        val m = manager(adapter)
        m.connect(device); runCurrent()
        assertEquals(ClearCodesTokenResult.Refused(ClearCodesRefusal.CODES_NOT_READ), m.issueClearCodesToken(vehicle))
        assertNull(m.codeReadSession.value)
        assertFalse("04" in adapter.written)
    }

    @Test fun `a read of another vehicle does not count`() = runTest {
        val m = connectedAndRead()
        assertEquals(ClearCodesTokenResult.Refused(ClearCodesRefusal.CODES_NOT_READ), m.issueClearCodesToken("audi"))
    }

    @Test fun `a failed read does not count and drops an earlier one`() = runTest {
        val adapter = engineOff()
        val m = connectedAndRead(adapter)
        assertNotNull(m.codeReadSession.value)
        adapter.responses["03"] = "?"
        assertTrue(m.readTroubleCodes(vehicle) is CodeReadResult.NoResponse)
        assertNull(m.codeReadSession.value)
        assertEquals(ClearCodesTokenResult.Refused(ClearCodesRefusal.CODES_NOT_READ), m.issueClearCodesToken(vehicle))
    }

    @Test fun `a new connection must read codes again`() = runTest {
        val m = manager(engineOff(), engineOff())
        m.connect(device); runCurrent()
        m.readTroubleCodes(vehicle)
        val t = m.token()
        m.connect(device); runCurrent() // reconnects: new connection
        assertNull(m.codeReadSession.value)
        assertEquals(ClearCodesResult.Refused(ClearCodesRefusal.CODES_NOT_READ), m.clearCodes(vehicle, t))
    }

    @Test fun `vehicle not responding is refused`() = runTest {
        val silent = FakeElmAdapter(vehicleOn = false)
        val m = manager(silent)
        m.connect(device); runCurrent()
        assertEquals(ClearCodesTokenResult.Refused(ClearCodesRefusal.VEHICLE_NOT_RESPONDING), m.issueClearCodesToken(vehicle))
        assertFalse("04" in silent.written)
    }

    @Test fun `running engine is refused`() = runTest {
        val adapter = FakeElmAdapter(mapOf("04" to "44")) // default fake idles at 800 rpm
        val m = connectedAndRead(adapter)
        assertEquals(ClearCodesResult.Refused(ClearCodesRefusal.ENGINE_RUNNING), m.clearCodes(vehicle, m.token()))
        assertFalse("04" in adapter.written)
    }

    @Test fun `unreadable rpm is refused`() = runTest {
        val adapter = engineOff(mapOf("010C" to "NO DATA"))
        val m = connectedAndRead(adapter)
        assertEquals(ClearCodesResult.Refused(ClearCodesRefusal.ENGINE_STATE_UNKNOWN), m.clearCodes(vehicle, m.token()))
        assertFalse("04" in adapter.written)
    }

    // ---- Token rules at the manager --------------------------------------------------------------

    @Test fun `no token, an expired token and a spent token are refused`() = runTest {
        val adapter = engineOff()
        val m = connectedAndRead(adapter)
        val expired = m.token()
        clock = clock.plusSeconds(61)
        assertEquals(ClearCodesResult.Refused(ClearCodesRefusal.CONFIRMATION_EXPIRED), m.clearCodes(vehicle, expired))
        assertFalse("04" in adapter.written)

        val spent = m.token()
        assertTrue(m.clearCodes(vehicle, spent) is ClearCodesResult.Cleared)
        m.readTroubleCodes(vehicle)
        assertEquals(ClearCodesResult.Refused(ClearCodesRefusal.NOT_CONFIRMED), m.clearCodes(vehicle, spent))
        assertEquals(1, adapter.written.count { it == "04" })
    }

    @Test fun `cancelling the dialog revokes the token`() = runTest {
        val adapter = engineOff()
        val m = connectedAndRead(adapter)
        val t = m.token()
        m.cancelClearCodes()
        assertEquals(ClearCodesResult.Refused(ClearCodesRefusal.NOT_CONFIRMED), m.clearCodes(vehicle, t))
        assertFalse("04" in adapter.written)
    }

    @Test fun `a token is void once the codes are read again`() = runTest {
        val adapter = engineOff()
        val m = connectedAndRead(adapter)
        val t = m.token()
        clock = clock.plusSeconds(5)
        m.readTroubleCodes(vehicle) // a new read: the user saw different codes than the token was for
        assertEquals(ClearCodesResult.Refused(ClearCodesRefusal.CODES_CHANGED), m.clearCodes(vehicle, t))
        assertFalse("04" in adapter.written)
    }

    @Test fun `a token for another vehicle is refused`() = runTest {
        val adapter = engineOff()
        val m = connectedAndRead(adapter)
        val t = m.token()
        // The only read belongs to "bmw"; asking to clear "audi" fails before the gate.
        assertEquals(ClearCodesResult.Refused(ClearCodesRefusal.CODES_NOT_READ), m.clearCodes("audi", t))
        assertFalse("04" in adapter.written)
    }

    // ---- Sending, interpreting, re-reading, recording ---------------------------------------------

    @Test fun `positive response clears, re-reads and records history`() = runTest {
        val adapter = engineOff()
        adapter.onCommand = { cmd ->
            if (cmd == "04") {
                adapter.responses["03"] = "43 00"
                adapter.responses["07"] = "47 00"
            }
        }
        val m = connectedAndRead(adapter)
        val before = history.latestTroubleCodeRead(vehicle).first()!!
        assertEquals(listOf("P0171"), before.codes.map { it.code })
        clock = clock.plusSeconds(10)

        val result = m.clearCodes(vehicle, m.token())

        assertEquals(ClearCodesResult.Cleared(remaining = 0), result)
        assertEquals(1, adapter.written.count { it == "04" })
        val written = adapter.written
        assertTrue("03 must be re-read after 04", written.indexOf("04") < written.lastIndexOf("03"))
        val after = history.latestTroubleCodeRead(vehicle).first()!!
        assertTrue(after.readAt.isAfter(before.readAt))
        assertTrue(after.codes.isEmpty())
        val note = history.observations(vehicle).first().single()
        assertEquals(ObservationKind.NOTE, note.kind)
        assertTrue(note.text.contains("cleared by you"))
        assertTrue(note.text.contains("P0171"))
        assertTrue(note.text.contains("Readiness monitors were reset"))
    }

    @Test fun `positive response but codes remain is reported honestly`() = runTest {
        val adapter = engineOff() // "03" keeps reporting P0171
        val m = connectedAndRead(adapter)
        clock = clock.plusSeconds(10)
        val result = m.clearCodes(vehicle, m.token()) as ClearCodesResult.Cleared
        assertEquals(1, result.remaining)
        assertTrue(history.observations(vehicle).first().single().text.contains("Codes reported afterwards: 1"))
    }

    @Test fun `negative or missing answer is never reported as cleared`() = runTest {
        for (reply in listOf("NO DATA", "7F 04 22", "?", "CAN ERROR")) {
            val adapter = engineOff(mapOf("04" to reply))
            val m = connectedAndRead(adapter)
            clock = clock.plusSeconds(10)
            val result = m.clearCodes(vehicle, m.token())
            assertTrue(reply, result is ClearCodesResult.NotAccepted)
            assertEquals(reply, 1, (result as ClearCodesResult.NotAccepted).remaining)
            assertTrue(history.observations(vehicle).first().none { it.text.contains("Readiness monitors were reset") })
        }
    }

    @Test fun `a clear that times out is never retried and reports no answer`() = runTest {
        val adapter = engineOff()
        adapter.timeouts["04"] = 5
        val m = connectedAndRead(adapter)
        clock = clock.plusSeconds(10)
        val result = m.clearCodes(vehicle, m.token())
        assertTrue(result is ClearCodesResult.NoAnswer)
        assertEquals(1, adapter.written.count { it == "04" })
    }

    @Test fun `only the positive response 44 counts`() {
        assertTrue(Elm327ResponseParser.isClearCodesAccepted("44"))
        assertTrue(Elm327ResponseParser.isClearCodesAccepted("SEARCHING...\r44"))
        assertFalse(Elm327ResponseParser.isClearCodesAccepted("NO DATA"))
        assertFalse(Elm327ResponseParser.isClearCodesAccepted("7F 04 22"))
        assertFalse(Elm327ResponseParser.isClearCodesAccepted("43 01 01 71"))
        assertFalse(Elm327ResponseParser.isClearCodesAccepted(""))
    }

    @Test fun `the action is logged without a vehicle identifier`() = runTest {
        val log = com.aicarcompanion.app.connectivity.ConnectionLog()
        val adapter = engineOff()
        val queue = ArrayDeque(listOf(adapter))
        val m = ObdManager(
            backgroundScope, { queue.removeFirst() }, history, now = { clock }, log = log,
            clearCodesAllowed = { true },
        )
        m.connect(device); runCurrent()
        m.readTroubleCodes(vehicle)
        m.clearCodes(vehicle, m.token())
        val text = log.export()
        assertTrue(text.contains("Clear codes sent"))
        assertTrue(text.contains("Clear codes result"))
        assertFalse(text.contains(vehicle))
    }
}
