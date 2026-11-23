package com.aicarcompanion.app.ui.livedata

import com.aicarcompanion.app.data.memory.InMemoryDashboardRepository
import com.aicarcompanion.app.data.memory.InMemoryVehicleRepository
import com.aicarcompanion.app.diagnostics.obd.AdapterDevice
import com.aicarcompanion.app.diagnostics.obd.ObdConnection
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.domain.livedata.DashboardLayout
import com.aicarcompanion.app.domain.livedata.GaugeSize
import com.aicarcompanion.app.domain.livedata.LivePollSnapshot
import com.aicarcompanion.app.domain.livedata.LiveSensorCatalog
import com.aicarcompanion.app.domain.livedata.SensorValueState
import com.aicarcompanion.app.domain.model.DiagnosticTroubleCode
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.domain.repository.DiagnosticsRepository
import com.aicarcompanion.app.domain.repository.DashboardRepository
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LiveDataViewModelTest {

    private class FakeDiagnostics : DiagnosticsRepository {
        val requests = mutableListOf<Set<Int>>()
        var collecting = 0
        val live = MutableStateFlow<Map<Int, SensorValueState>>(emptyMap())
        override fun troubleCodes(vehicleId: String): Flow<List<DiagnosticTroubleCode>> = flowOf(emptyList())
        override fun sensorReadings(vehicleId: String): Flow<List<SensorReading>> = flowOf(emptyList())
        override fun liveSensors(pids: Set<Int>): Flow<Map<Int, SensorValueState>> = callbackFlow {
            requests += pids
            collecting++
            val job = launch { live.collect { trySend(it.filterKeys { k -> k in pids }) } }
            awaitClose { collecting--; job.cancel() }
        }
    }

    private class FakeObd : ObdConnection {
        override val state = MutableStateFlow<ObdConnectionState>(ObdConnectionState.Disconnected)
        override val liveReadings: Flow<List<SensorReading>> = flowOf(emptyList())
        override val supportedPids: StateFlow<Set<Int>> = MutableStateFlow(emptySet())
        override fun livePids(pids: Set<Int>): Flow<LivePollSnapshot> = flowOf(LivePollSnapshot())
        override fun connect(device: AdapterDevice) = Unit
        override fun disconnect() = Unit
        override fun retryVehicle() = Unit
    }

    private val dispatcher = UnconfinedTestDispatcher()
    private val car = Vehicle("bmw", 2011, "BMW", "528i", engine = EngineSpec())
    private val diagnostics = FakeDiagnostics()
    private val dashboards = InMemoryDashboardRepository()
    private var ids = 0

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = LiveDataViewModel(InMemoryVehicleRepository(listOf(car)), diagnostics, FakeObd(), dashboards) { "id${++ids}" }

    private fun TestScope.watch(vm: LiveDataViewModel) = backgroundScope.launch { vm.state.collect { } }

    @Test fun `a new vehicle starts on the default layout and asks only for its sensors`() = runTest {
        val vm = vm(); watch(vm); runCurrent()
        val active = vm.state.value.active!!
        assertEquals(DashboardLayout.DEFAULT_NAME, active.name)
        assertEquals(LiveSensorCatalog.defaultPids.toSet(), diagnostics.requests.last())
    }

    @Test fun `rendering and sensor selection share one dashboard subscription`() = runTest {
        var subscriptions = 0
        val repository = object : DashboardRepository by dashboards {
            override fun layouts(vehicleId: String) = dashboards.layouts(vehicleId).onStart { subscriptions++ }
        }
        val vm = LiveDataViewModel(InMemoryVehicleRepository(listOf(car)), diagnostics, FakeObd(), repository)
        watch(vm)
        runCurrent()
        assertNotNull(vm.state.value.active)
        assertTrue(diagnostics.requests.isNotEmpty())
        assertEquals(1, subscriptions)
    }

    @Test fun `adding removing and resizing are saved per vehicle and change what is requested`() = runTest {
        val vm = vm(); watch(vm); runCurrent()
        vm.addGauge(0x2F); runCurrent()
        assertEquals(5, vm.state.value.active!!.gauges.size)
        assertTrue(0x2F in diagnostics.requests.last())
        vm.resizeGauge(0x2F, GaugeSize.LARGE); runCurrent()
        assertEquals(GaugeSize.LARGE, vm.state.value.active!!.gauges.last().size)
        vm.removeGauge(0x0C); runCurrent()
        assertTrue(0x0C !in diagnostics.requests.last())
        assertEquals(1, dashboards.layouts("bmw").first().size)
        assertTrue(dashboards.layouts("other").first().isEmpty())
    }

    @Test fun `the ninth gauge is refused with a message`() = runTest {
        val vm = vm(); watch(vm); runCurrent()
        LiveSensorCatalog.all.map { it.pid }.filter { it !in LiveSensorCatalog.defaultPids }.take(4).forEach { vm.addGauge(it); runCurrent() }
        assertEquals(8, vm.state.value.active!!.gauges.size)
        vm.addGauge(LiveSensorCatalog.all.map { it.pid }.first { it !in vm.state.value.active!!.pids }); runCurrent()
        assertEquals(8, vm.state.value.active!!.gauges.size)
        assertNotNull(vm.state.value.notice)
    }

    @Test fun `named layouts are independent and selectable`() = runTest {
        val vm = vm(); watch(vm); runCurrent()
        vm.newLayout("Track"); runCurrent()
        assertEquals("Track", vm.state.value.active!!.name)
        assertTrue(vm.state.value.active!!.gauges.isEmpty())
        vm.addGauge(0x0C); runCurrent()
        assertEquals(setOf(0x0C), diagnostics.requests.last())
        val trackId = vm.state.value.active!!.id
        // Keep the original reachable: save a second layout, then switch.
        vm.newLayout("Economy"); runCurrent()
        vm.selectLayout(trackId); runCurrent()
        assertEquals("Track", vm.state.value.active!!.name)
        assertEquals(listOf("Track", "Economy"), vm.state.value.layouts.map { it.name })
        vm.renameLayout("Track day"); runCurrent()
        assertEquals("Track day", vm.state.value.active!!.name)
        vm.deleteLayout(); runCurrent()
        assertEquals(listOf("Economy"), vm.state.value.layouts.map { it.name })
    }

    @Test fun `a blank layout name is refused`() = runTest {
        val vm = vm(); watch(vm); runCurrent()
        vm.newLayout("   "); runCurrent()
        assertNotNull(vm.state.value.notice)
        assertEquals(DashboardLayout.DEFAULT_NAME, vm.state.value.active!!.name)
    }

    @Test fun `all sensors tab asks for every catalog sensor and nothing is polled once the screen is left`() = runTest {
        val vm = vm()
        val job = watch(vm); runCurrent()
        vm.selectTab(WorkspaceTab.ALL_SENSORS); runCurrent()
        assertEquals(LiveSensorCatalog.all.map { it.pid }.toSet(), diagnostics.requests.last())
        assertEquals(1, diagnostics.collecting)
        job.cancel(); runCurrent()
        advanceTimeBy(6_000); runCurrent()
        assertEquals(0, diagnostics.collecting)
    }

    @Test fun `line graph history keeps only real live readings, once per timestamp`() = runTest {
        val vm = vm(); watch(vm); runCurrent()
        val t = Instant.parse("2026-11-21T12:00:00Z")
        diagnostics.live.value = mapOf(0x0C to SensorValueState.Live(800.0, t)); runCurrent()
        diagnostics.live.value = mapOf(0x0C to SensorValueState.Live(800.0, t)); runCurrent() // same sample again
        diagnostics.live.value = mapOf(0x0C to SensorValueState.Live(900.0, t.plusSeconds(1))); runCurrent()
        diagnostics.live.value = mapOf(0x0C to SensorValueState.Stale(900.0, t.plusSeconds(1))); runCurrent()
        diagnostics.live.value = mapOf(0x0C to SensorValueState.Sample(2000.0, t)); runCurrent()
        assertEquals(listOf(800.0, 900.0), vm.state.value.history[0x0C])
    }

    @Test fun `no vehicle means no layout and no polling`() = runTest {
        val vm = LiveDataViewModel(InMemoryVehicleRepository(), diagnostics, FakeObd(), dashboards)
        watch(vm); runCurrent()
        assertTrue(!vm.state.value.hasVehicle)
        assertNull(vm.state.value.active)
        assertTrue(diagnostics.requests.isEmpty())
    }
}
