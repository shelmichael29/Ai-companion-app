package com.aicarcompanion.app.phase41

import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.MapKeyValueStore
import com.aicarcompanion.app.data.memory.InMemoryHistoryRepository
import com.aicarcompanion.app.diagnostics.FakeElmAdapter
import com.aicarcompanion.app.diagnostics.obd.AdapterAutoSelector
import com.aicarcompanion.app.diagnostics.obd.AdapterDevice
import com.aicarcompanion.app.diagnostics.obd.AdapterInfo
import com.aicarcompanion.app.diagnostics.obd.AdapterRadio
import com.aicarcompanion.app.diagnostics.obd.AdaptiveTiming
import com.aicarcompanion.app.diagnostics.obd.ConnectionStats
import com.aicarcompanion.app.diagnostics.obd.ConnectionTroubleshooter
import com.aicarcompanion.app.diagnostics.obd.ConsoleResult
import com.aicarcompanion.app.diagnostics.obd.DiagnosticStep
import com.aicarcompanion.app.diagnostics.obd.Elm327Session
import com.aicarcompanion.app.diagnostics.obd.ExchangeOutcome
import com.aicarcompanion.app.diagnostics.obd.ObdConfigStore
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionConfig
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdManager
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import com.aicarcompanion.app.diagnostics.obd.ObdProtocol
import com.aicarcompanion.app.diagnostics.obd.ObdTransport
import com.aicarcompanion.app.diagnostics.obd.ReadOnlyCommandPolicy
import com.aicarcompanion.app.diagnostics.obd.TrafficLog
import com.aicarcompanion.app.diagnostics.obd.TransportSupport
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ObdConnectionSettingsTest {

    private val device = AdapterDevice("OBDII", "00:1D:A5:00:00:01", AdapterRadio.CLASSIC)

    private fun TestScope.settle(millis: Long = 0) {
        if (millis > 0) advanceTimeBy(millis)
        runCurrent()
    }

    private class Harness(val manager: ObdManager, val adapters: MutableList<FakeElmAdapter>)

    private fun TestScope.harness(
        config: () -> ObdConnectionConfig = { ObdConnectionConfig.RECOMMENDED },
        make: () -> FakeElmAdapter = { FakeElmAdapter() },
    ): Harness {
        val opened = mutableListOf<FakeElmAdapter>()
        val m = ObdManager(
            backgroundScope, { make().also { opened += it } }, InMemoryHistoryRepository(),
            now = { Instant.parse("2026-11-15T12:00:00Z") }, config = config,
        )
        return Harness(m, opened)
    }

    private suspend fun init(config: ObdConnectionConfig, adapter: FakeElmAdapter = FakeElmAdapter()): Pair<FakeElmAdapter, Elm327Session> {
        val s = Elm327Session(adapter)
        s.initialize(config)
        return adapter to s
    }

    // ---- Default stays automatic ------------------------------------------------------------

    @Test fun `recommended config sends the unchanged automatic init sequence`() = runTest {
        val (a, _) = init(ObdConnectionConfig.RECOMMENDED)
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS1", "ATH0", "ATAT1", "ATSP0", "0100", "0120", "0140", "ATDPN"), a.written)
    }

    @Test fun `everyday and DIY always use recommended settings, advanced uses saved ones`() {
        val custom = ObdConnectionConfig(protocol = ObdProtocol.CAN_11_500, pollIntervalMillis = 250, autoReconnect = false)
        assertEquals(ObdConnectionConfig.RECOMMENDED, ObdConnectionConfig.effectiveFor(ExplanationLevel.EVERYDAY, custom))
        assertEquals(ObdConnectionConfig.RECOMMENDED, ObdConnectionConfig.effectiveFor(ExplanationLevel.DIY, custom))
        assertEquals(custom, ObdConnectionConfig.effectiveFor(ExplanationLevel.ADVANCED, custom))
    }

    // ---- Protocol -----------------------------------------------------------------------------

    @Test fun `manual protocol with fallback uses ATSP A-n`() = runTest {
        val (a, s) = init(ObdConnectionConfig(protocol = ObdProtocol.CAN_11_500), FakeElmAdapter(mapOf("ATSPA6" to "OK")))
        assertTrue("ATSPA6" in a.written)
        assertEquals(true, s.capabilities.manualProtocol)
    }

    @Test fun `strict manual protocol uses ATSP n`() = runTest {
        val (a, _) = init(ObdConnectionConfig(protocol = ObdProtocol.ISO_9141_2, protocolFallbackToAuto = false), FakeElmAdapter(mapOf("ATSP3" to "OK")))
        assertTrue("ATSP3" in a.written)
        assertFalse("ATSP0" in a.written)
    }

    @Test fun `protocol the adapter rejects falls back to automatic`() = runTest {
        val (a, s) = init(ObdConnectionConfig(protocol = ObdProtocol.J1850_PWM)) // fake answers "?" to ATSPA1
        assertEquals(false, s.capabilities.manualProtocol)
        assertTrue(a.written.indexOf("ATSP0") > a.written.indexOf("ATSPA1"))
    }

    // ---- Timing -------------------------------------------------------------------------------

    @Test fun `adaptive timing off sends ATAT0 and a fixed adapter timeout`() = runTest {
        val cfg = ObdConnectionConfig(adaptiveTiming = AdaptiveTiming.OFF, adapterTimeoutMillis = 200)
        assertEquals("ATST30", cfg.adapterTimeoutCommand) // 200 ms / 4.096 = 48 = 0x30
        val (a, s) = init(cfg, FakeElmAdapter(mapOf("ATAT0" to "OK", "ATST30" to "OK")))
        assertTrue(a.written.containsAll(listOf("ATAT0", "ATST30")))
        assertEquals(true, s.capabilities.fixedTimeout)
    }

    @Test fun `unsupported adaptive timing is detected so the UI can say so`() = runTest {
        val (_, s) = init(ObdConnectionConfig.RECOMMENDED) // fake answers "?" to ATAT1 like many clones
        assertEquals(false, s.capabilities.adaptiveTiming)
    }

    @Test fun `aggressive timing falls back to normal adaptive when rejected`() = runTest {
        val (a, s) = init(ObdConnectionConfig(adaptiveTiming = AdaptiveTiming.AGGRESSIVE))
        assertEquals(false, s.capabilities.aggressiveTiming)
        assertTrue(a.written.indexOf("ATAT1") > a.written.indexOf("ATAT2"))
    }

    @Test fun `compact responses send ATS0 and still parse`() = runTest {
        val adapter = FakeElmAdapter(mapOf("ATS0" to "OK", "010C" to "410C0C80"))
        val (a, s) = init(ObdConnectionConfig(compactResponses = true), adapter)
        assertTrue("ATS0" in a.written && "ATS1" !in a.written)
        assertEquals(true, s.capabilities.compactResponses)
        assertEquals(800.0, com.aicarcompanion.app.diagnostics.obd.Elm327ResponseParser.decodePid(s.execute("010C"), ObdPid.ENGINE_RPM)!!, 0.01)
    }

    @Test fun `timeouts are retried as configured but resets never are`() = runTest {
        val adapter = FakeElmAdapter()
        val retries = mutableListOf<Unit>()
        val s = Elm327Session(adapter, onRetry = { retries += Unit })
        s.retries = 2
        adapter.timeouts["010C"] = 2
        assertTrue(s.execute("010C").contains("41 0C"))
        assertEquals(2, retries.size)
        adapter.timeouts["ATZ"] = 1
        runCatching { s.execute("ATZ") }
        assertEquals(1, adapter.written.count { it == "ATZ" })
    }

    // ---- Persistence and defaults ---------------------------------------------------------------

    @Test fun `settings persist, clamp bad values and restore recommended defaults`() {
        val kv = MapKeyValueStore()
        val store = ObdConfigStore(kv)
        store.update { it.copy(protocol = ObdProtocol.CAN_29_500, pollIntervalMillis = 10, retries = 99, communicationLog = true) }
        val reloaded = ObdConfigStore(kv).config.value
        assertEquals(ObdProtocol.CAN_29_500, reloaded.protocol)
        assertEquals(ObdConnectionConfig.POLL_RANGE.first, reloaded.pollIntervalMillis)
        assertEquals(ObdConnectionConfig.RETRY_RANGE.last, reloaded.retries)
        assertFalse(reloaded.isRecommended)
        store.restoreRecommended()
        assertEquals(ObdConnectionConfig.RECOMMENDED, ObdConfigStore(kv).config.value)
    }

    // ---- Manager behavior ------------------------------------------------------------------------

    @Test fun `protocol change while connected re-initializes the adapter`() = runTest {
        var cfg = ObdConnectionConfig.RECOMMENDED
        val h = harness(config = { cfg })
        h.manager.connect(device); settle()
        assertEquals(1, h.adapters.size)
        cfg = cfg.copy(pollIntervalMillis = 2_000) // live setting: no reconnect
        h.manager.applyConfig(); settle()
        assertEquals(1, h.adapters.size)
        cfg = cfg.copy(protocol = ObdProtocol.CAN_11_500)
        h.manager.applyConfig(); settle()
        assertEquals(2, h.adapters.size)
        assertTrue("ATSPA6" in h.adapters.last().written)
        assertTrue(h.manager.state.value is ObdConnectionState.Connected)
    }

    @Test fun `automatic reconnection off reports the drop instead of retrying`() = runTest {
        val first = FakeElmAdapter()
        var n = 0
        val h = harness(config = { ObdConnectionConfig(autoReconnect = false) }, make = { if (n++ == 0) first else FakeElmAdapter() })
        h.manager.connect(device); settle()
        first.failAfterWrites = first.written.size
        h.manager.readLiveValue(ObdPid.ENGINE_RPM); settle(30_000)
        assertTrue(h.manager.state.value is ObdConnectionState.Failed)
        assertEquals(1, h.adapters.size)
    }

    @Test fun `reset adapter runs the full init again on the same adapter`() = runTest {
        val h = harness()
        h.manager.connect(device); settle()
        h.manager.resetAdapter(); settle()
        assertEquals(2, h.adapters.size)
        assertEquals("ATZ", h.adapters.last().written.first())
        assertTrue(h.manager.state.value is ObdConnectionState.Connected)
    }

    @Test fun `adapter info and stats are reported`() = runTest {
        val h = harness()
        h.manager.connect(device); settle()
        val info = h.manager.refreshAdapterDetails()!!
        assertEquals("ELM327 v1.5", info.firmware)
        assertEquals(12.4, info.voltage!!, 0.01)
        assertEquals("ISO 15765-4 CAN (11-bit, 500 kbaud)", info.protocolName)
        assertTrue(h.manager.stats.value.requests >= 11)
    }

    @Test fun `connection test passes on a healthy link and fails when disconnected`() = runTest {
        val h = harness()
        assertEquals(DiagnosticStep.Result.FAIL, h.manager.runDiagnostics().overall)
        h.manager.connect(device); settle()
        val report = h.manager.runDiagnostics()
        assertTrue(report.steps.none { it.result == DiagnosticStep.Result.FAIL })
        assertTrue(report.steps.any { it.title == "Vehicle responds" && it.result == DiagnosticStep.Result.PASS })
    }

    // ---- Expert console safety -------------------------------------------------------------------

    @Test fun `console blocks anything that could change the car or break parsing`() = runTest {
        val h = harness()
        h.manager.connect(device); settle()
        val before = h.adapters.last().written.size
        for (cmd in listOf("04", "14", "ATSH7E0", "2E F1 90 00", "31 01", "ATE1", "ATH1", "ATZ", "ATD", "ATSP1", "11 01", "08 01")) {
            assertTrue(cmd, h.manager.sendConsoleCommand(cmd) is ConsoleResult.Blocked)
        }
        assertEquals("blocked commands never reach the adapter", before, h.adapters.last().written.size)
        val ok = h.manager.sendConsoleCommand("01 0C")
        assertTrue(ok is ConsoleResult.Response && ok.text.contains("41 0C"))
    }

    @Test fun `policy allows protocol fallback form but still blocks clearing codes`() {
        assertTrue(ReadOnlyCommandPolicy.isAllowed("ATSPA6"))
        assertFalse(ReadOnlyCommandPolicy.isAllowed("04"))
        assertFalse(ReadOnlyCommandPolicy.isConsoleAllowed("ATSPA6"))
    }

    // ---- Privacy-conscious logging ---------------------------------------------------------------

    @Test fun `traffic log is off by default, redacts identifiers and clears when disabled`() {
        val log = TrafficLog(clock = { 0L })
        log.onExchange("010C", "41 0C 0C 80", 20, ExchangeOutcome.OK)
        assertTrue(log.entries.value.isEmpty())
        log.enabled = true
        log.onExchange("010C", "41 0C 0C 80", 20, ExchangeOutcome.OK)
        log.onExchange("0902", "014\r0: 49 02 01 57 42 41\r1: ...", 80, ExchangeOutcome.OK)
        log.onExchange("ATI", "VIN WBAFR7C51BC000000 seen", 5, ExchangeOutcome.OK)
        log.onExchange("0100", "4100BE3FA813BE3FA813", 30, ExchangeOutcome.OK) // long hex is not a VIN
        val e = log.entries.value
        assertEquals("41 0C 0C 80", e[0].response)
        assertEquals(TrafficLog.REDACTED, e[1].response)
        assertFalse(e[2].response.contains("WBAFR7C51BC000000"))
        assertEquals("4100BE3FA813BE3FA813", e[3].response)
        assertFalse(log.export().contains("00:1D:A5"))
        log.enabled = false
        assertTrue(log.entries.value.isEmpty())
    }

    @Test fun `manager feeds the log only when enabled in settings`() = runTest {
        val h = harness(config = { ObdConnectionConfig(communicationLog = true) })
        h.manager.connect(device); settle()
        assertTrue(h.manager.trafficLog.entries.value.any { it.command == "ATZ" })
        val off = harness()
        off.manager.connect(device); settle()
        assertTrue(off.manager.trafficLog.entries.value.isEmpty())
    }

    // ---- Everyday automatic adapter selection -----------------------------------------------------

    @Test fun `auto selection prefers last adapter, then a single OBD-looking device`() {
        val obd = AdapterDevice("OBDII", "A", AdapterRadio.CLASSIC)
        val vlink = AdapterDevice("V-LINK", "B", AdapterRadio.CLASSIC)
        val buds = AdapterDevice("Galaxy Buds", "C", AdapterRadio.DUAL)
        val ble = AdapterDevice("OBD BLE", "D", AdapterRadio.LE_ONLY)
        assertEquals(vlink, (AdapterAutoSelector.choose(listOf(obd, vlink, buds), "B") as AdapterAutoSelector.Choice.Selected).device)
        assertEquals(obd, (AdapterAutoSelector.choose(listOf(obd, buds, ble), null) as AdapterAutoSelector.Choice.Selected).device)
        assertTrue(AdapterAutoSelector.choose(listOf(obd, vlink), null) is AdapterAutoSelector.Choice.NeedsUser)
        assertTrue(AdapterAutoSelector.choose(listOf(buds), null) is AdapterAutoSelector.Choice.NeedsUser) // never guesses headphones
        assertEquals(AdapterAutoSelector.Choice.NoneFound, AdapterAutoSelector.choose(listOf(ble), null))
    }

    // ---- Troubleshooting and transports -------------------------------------------------------------

    @Test fun `troubleshooting tips react to state, voltage, clones and timeouts`() {
        val tips = ConnectionTroubleshooter.tips(
            ObdConnectionState.Connected(ObdTransport.BLUETOOTH_CLASSIC, "OBDII", null),
            ConnectionStats(requests = 20, timeouts = 8),
            AdapterInfo(firmware = "ELM327 v2.1", voltage = 11.2),
        )
        assertTrue(tips.any { it.contains("ignition") })
        assertTrue(tips.any { it.contains("timing out") })
        assertTrue(tips.any { it.contains("11.2") })
        assertTrue(tips.any { it.contains("clone") })
        assertTrue(ConnectionTroubleshooter.tips(ObdConnectionState.Connected(ObdTransport.BLUETOOTH_CLASSIC, "OBDII", "CAN"), ConnectionStats(), null).isEmpty())
    }

    @Test fun `only implemented transports are offered`() {
        assertEquals(setOf(ObdTransport.BLUETOOTH_CLASSIC), ObdTransport.entries.filter(TransportSupport::isImplemented).toSet())
    }

    @Test fun `reconnect delays follow settings`() {
        assertEquals(listOf(2_000L, 5_000L, 10_000L), ObdConnectionConfig.RECOMMENDED.reconnectDelaysMillis)
        assertEquals(5, ObdConnectionConfig(reconnectAttempts = 5).reconnectDelaysMillis.size)
        assertTrue(ObdConnectionConfig(autoReconnect = false).reconnectDelaysMillis.isEmpty())
    }
}
