@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.aicarcompanion.app.diagnostics

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.aicarcompanion.app.assistant.conversation.LiveValueResult
import com.aicarcompanion.app.data.DefaultDiagnosticsRepository
import com.aicarcompanion.app.data.local.AppSettings
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.InMemorySettingsStore
import com.aicarcompanion.app.data.memory.InMemoryHistoryRepository
import com.aicarcompanion.app.data.memory.InMemoryVehicleRepository
import com.aicarcompanion.app.data.photos.VehiclePhotoStore
import com.aicarcompanion.app.diagnostics.obd.AdapterDevice
import com.aicarcompanion.app.diagnostics.obd.AdapterRadio
import com.aicarcompanion.app.diagnostics.obd.ElmLink
import com.aicarcompanion.app.diagnostics.obd.LinkHealth
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdManager
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import com.aicarcompanion.app.diagnostics.obd.RecoveryPolicy
import com.aicarcompanion.app.diagnostics.dtc.GenericDtcCatalog
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.ui.home.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Owner decision 2026-11-26 (Phase 1D follow-up L-1): while Home is open at Everyday or DIY it polls one
 * heartbeat sensor (engine speed, else coolant temperature, else control module voltage), not the whole
 * list. It still notices a lost vehicle link within [RecoveryPolicy.DEGRADE_AFTER] poll rounds, and voice
 * answers no longer queue behind a dozen polls. The Adapter screen and the internal Advanced Home keep the
 * full list. Everything runs on a scripted adapter in virtual time; never real hardware.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HomeHeartbeatPollingTest {
    private val device = AdapterDevice("OBDII", "00:1D:A5:00:00:01", AdapterRadio.CLASSIC)
    private val level = MutableStateFlow(ExplanationLevel.EVERYDAY)

    @Before fun main() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun resetMain() = Dispatchers.resetMain()

    /** A [FakeElmAdapter] whose every answer takes [millis], like a real adapter on the car's bus. */
    private class SlowAdapter(val inner: FakeElmAdapter = FakeElmAdapter(), private val millis: Long = 0) : ElmLink by inner {
        override suspend fun readUntilPrompt(timeoutMillis: Long): String {
            if (millis > 0) delay(millis)
            return inner.readUntilPrompt(timeoutMillis)
        }
    }

    private fun TestScope.connected(adapter: ElmLink): ObdManager {
        val m = ObdManager(backgroundScope, { adapter }, InMemoryHistoryRepository(), now = { Instant.parse("2026-11-26T12:00:00Z") })
        m.connect(device)
        advanceTimeBy(30_000); runCurrent()
        val s = m.state.value
        assertTrue("not connected: $s", s is ObdConnectionState.Connected && s.vehicleProtocol != null)
        return m
    }

    private fun TestScope.homeViewModel(m: ObdManager, demo: Boolean = false): HomeViewModel {
        val v = Vehicle("v", 2011, "BMW", "528i", engine = EngineSpec(3.0, 6, com.aicarcompanion.app.domain.model.EngineLayout.INLINE))
        val history = InMemoryHistoryRepository()
        val settings = InMemorySettingsStore(AppSettings(demoDataEnabled = demo))
        val diagnostics = DefaultDiagnosticsRepository(
            history, settings, GenericDtcCatalog, m.liveReadings,
            heartbeat = m.connectionHeartbeat, livePids = m::livePids, connection = m.state,
        )
        val vm = HomeViewModel(
            InMemoryVehicleRepository(listOf(v)), diagnostics, history, m,
            VehiclePhotoStore(ApplicationProvider.getApplicationContext()), history, level,
        )
        vm.state.onEach { }.launchIn(backgroundScope)
        return vm
    }

    private fun pollCommands(adapter: FakeElmAdapter, after: Int) = adapter.written.drop(after).filter { it.startsWith("01") }.toSet()

    private fun TestScope.pollsWhileHomeIsOpen(at: ExplanationLevel): Set<String> {
        level.value = at
        val adapter = FakeElmAdapter()
        val m = connected(adapter)
        m.setLiveDetail(at)
        val before = adapter.written.size
        homeViewModel(m)
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        return pollCommands(adapter, before)
    }

    @Test fun `Home at Everyday requests only the engine speed heartbeat`() = runTest {
        assertEquals(setOf("010C"), pollsWhileHomeIsOpen(ExplanationLevel.EVERYDAY))
    }

    @Test fun `Home at DIY requests only the engine speed heartbeat`() = runTest {
        assertEquals(setOf("010C"), pollsWhileHomeIsOpen(ExplanationLevel.DIY))
    }

    @Test fun `Home at the internal Advanced level still polls the full list`() = runTest {
        val polled = pollsWhileHomeIsOpen(ExplanationLevel.ADVANCED)
        assertTrue("only $polled", polled.size > 5 && "010C" in polled && "010D" in polled && "0105" in polled)
    }

    @Test fun `the Adapter screen still gets the full list while Home is open`() = runTest {
        val adapter = FakeElmAdapter()
        val m = connected(adapter)
        val before = adapter.written.size
        homeViewModel(m)
        var shown = emptyList<String>()
        backgroundScope.launch { m.liveReadings.collect { list -> shown = list.map { it.name } } }
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertTrue("010D" in pollCommands(adapter, before))
        assertTrue("Adapter screen list: $shown", shown.size > 1)
    }

    @Test fun `demonstration mode never touches the adapter from Home`() = runTest {
        val adapter = FakeElmAdapter()
        val m = connected(adapter)
        val before = adapter.written.size
        homeViewModel(m, demo = true)
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(before, adapter.written.size)
    }

    @Test fun `without engine speed the heartbeat falls back to coolant temperature`() = runTest {
        // Supports 0x05 and 0x0D only: bits for 05 and 0D.
        val adapter = FakeElmAdapter(mapOf("0100" to "41 00 08 08 00 00", "0120" to "NO DATA", "0140" to "NO DATA"))
        val m = connected(adapter)
        val before = adapter.written.size
        homeViewModel(m)
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(setOf("0105"), pollCommands(adapter, before))
    }

    @Test fun `without engine speed or coolant the heartbeat falls back to module voltage`() = runTest {
        val adapter = FakeElmAdapter(mapOf("0100" to "41 00 00 00 00 01", "0120" to "41 20 00 00 00 01", "0140" to "41 40 40 00 00 00"))
        val m = connected(adapter)
        assertTrue(0x42 in m.supportedPids.value && 0x0C !in m.supportedPids.value && 0x05 !in m.supportedPids.value)
        val before = adapter.written.size
        homeViewModel(m)
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(setOf("0142"), pollCommands(adapter, before))
    }

    @Test fun `with none of the heartbeat sensors supported nothing is sent and nothing is invented`() = runTest {
        val adapter = FakeElmAdapter(mapOf("0100" to "41 00 00 08 00 00", "0120" to "NO DATA", "0140" to "NO DATA")) // 0x0D only
        val m = connected(adapter)
        val before = adapter.written.size
        val vm = homeViewModel(m)
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(emptySet<String>(), pollCommands(adapter, before))
        assertTrue(vm.state.value.readings.isEmpty())
    }

    @Test fun `the vehicle going silent is noticed within the degrade threshold while only the heartbeat polls`() = runTest {
        val adapter = FakeElmAdapter()
        val m = connected(adapter)
        homeViewModel(m)
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        assertEquals(LinkHealth.OK, (m.state.value as ObdConnectionState.Connected).health)
        adapter.vehicleOn = false
        val off = currentTime
        var noticedAt = -1L
        while (noticedAt < 0 && currentTime - off < 10_000) {
            advanceTimeBy(100); runCurrent()
            val s = m.state.value
            if (s !is ObdConnectionState.Connected || s.health != LinkHealth.OK) noticedAt = currentTime - off
        }
        // One heartbeat request per round at the default 1 s pause: DEGRADE_AFTER failed rounds, plus the one in flight.
        val bound = RecoveryPolicy.DEGRADE_AFTER * 1_000L
        assertTrue("noticed after $noticedAt ms (bound $bound ms)", noticedAt in 0..bound)
    }

    @Test fun `a five reading voice answer is not slowed by Home at Everyday`() = runTest {
        val pids = listOf(ObdPid.ENGINE_RPM, ObdPid.COOLANT_TEMP, ObdPid.CONTROL_MODULE_VOLTAGE, ObdPid.ENGINE_LOAD, ObdPid.THROTTLE_POSITION)
        val m = connected(SlowAdapter(millis = 100))
        homeViewModel(m)
        runCurrent(); advanceTimeBy(2_000)
        var took = -1L
        backgroundScope.launch {
            val start = currentTime
            pids.forEach { assertTrue(m.readLiveValue(it) is LiveValueResult.Value) }
            took = currentTime - start
        }
        advanceTimeBy(10_000); runCurrent()
        println("MEASURED voice 5-reading answer with Home open (Everyday): $took ms (alone 500 ms)")
        // Alone it takes 500 ms; with the full list polled it took 900 ms. One heartbeat request can be in flight at most once.
        assertTrue("took $took ms", took in 500..600)
    }

    @Test fun `a five reading voice answer is not slowed by Home at DIY`() = runTest {
        level.value = ExplanationLevel.DIY
        val pids = listOf(ObdPid.ENGINE_RPM, ObdPid.COOLANT_TEMP, ObdPid.CONTROL_MODULE_VOLTAGE, ObdPid.ENGINE_LOAD, ObdPid.THROTTLE_POSITION)
        val m = connected(SlowAdapter(millis = 100))
        m.setLiveDetail(ExplanationLevel.DIY)
        homeViewModel(m)
        runCurrent(); advanceTimeBy(2_000)
        var took = -1L
        backgroundScope.launch {
            val start = currentTime
            pids.forEach { assertTrue(m.readLiveValue(it) is LiveValueResult.Value) }
            took = currentTime - start
        }
        advanceTimeBy(10_000); runCurrent()
        println("MEASURED voice 5-reading answer with Home open (DIY): $took ms (alone 500 ms)")
        assertTrue("took $took ms", took in 500..600)
    }

}
