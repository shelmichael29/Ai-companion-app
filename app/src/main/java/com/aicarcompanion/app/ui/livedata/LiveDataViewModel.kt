package com.aicarcompanion.app.ui.livedata

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicarcompanion.app.diagnostics.obd.ObdConnection
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.domain.livedata.DashboardLayout
import com.aicarcompanion.app.domain.livedata.GaugeSize
import com.aicarcompanion.app.domain.livedata.GaugeSpec
import com.aicarcompanion.app.domain.livedata.GaugeStyle
import com.aicarcompanion.app.domain.livedata.LayoutError
import com.aicarcompanion.app.domain.livedata.LayoutResult
import com.aicarcompanion.app.domain.livedata.LiveSensorCatalog
import com.aicarcompanion.app.domain.livedata.SensorValueState
import com.aicarcompanion.app.domain.livedata.UnitChoice
import com.aicarcompanion.app.domain.repository.DashboardRepository
import com.aicarcompanion.app.domain.repository.DiagnosticsRepository
import com.aicarcompanion.app.domain.repository.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

enum class WorkspaceTab(val label: String) { DASHBOARD("Dashboard"), ALL_SENSORS("All sensors") }

data class LiveDataUiState(
    val hasVehicle: Boolean = false,
    val layouts: List<DashboardLayout> = emptyList(),
    val active: DashboardLayout? = null,
    val tab: WorkspaceTab = WorkspaceTab.DASHBOARD,
    val editing: Boolean = false,
    val states: Map<Int, SensorValueState> = emptyMap(),
    /** The last real values per sensor (oldest first), for line graphs. Never padded with invented points. */
    val history: Map<Int, List<Double>> = emptyMap(),
    val obdState: ObdConnectionState = ObdConnectionState.Disconnected,
    val notice: String? = null,
)

/**
 * The Live Data workspace. Sensors are requested only for what is on screen (the active
 * dashboard, or the full list on the All sensors tab) and only while the screen is collected.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveDataViewModel(
    private val vehicles: VehicleRepository,
    private val diagnostics: DiagnosticsRepository,
    obd: ObdConnection,
    private val dashboards: DashboardRepository,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {

    private val tab = MutableStateFlow(WorkspaceTab.DASHBOARD)
    private val editing = MutableStateFlow(false)
    private val notice = MutableStateFlow<String?>(null)
    private val history = MutableStateFlow<Map<Int, List<Double>>>(emptyMap())
    private val lastSampleAt = HashMap<Int, Instant>()

    private val layouts = vehicles.activeVehicleId.distinctUntilChanged().flatMapLatest { id ->
        if (id == null) flowOf(Triple<String?, List<DashboardLayout>, String?>(null, emptyList(), null))
        else combine(dashboards.layouts(id), dashboards.activeLayoutId(id)) { saved, activeId ->
            val all = saved.ifEmpty { listOf(DashboardLayout.default(id)) }
            Triple(id, all, activeId)
        }
    }.map { (id, all, activeId) -> Triple(id, all, all.firstOrNull { it.id == activeId } ?: all.firstOrNull()) }

    // Rendering and sensor selection share one set of Room observers while the UI is collected.
    private val visibleLayouts = layouts.shareIn(
        viewModelScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1,
    )

    private val requested = combine(visibleLayouts, tab) { (id, all, active), t ->
        when {
            id == null || all.isEmpty() -> emptySet()
            t == WorkspaceTab.ALL_SENSORS -> LiveSensorCatalog.all.map { it.pid }.toSet()
            else -> active?.pids.orEmpty()
        }
    }.distinctUntilChanged()

    private val states = requested.flatMapLatest { pids ->
        if (pids.isEmpty()) flowOf(emptyMap()) else diagnostics.liveSensors(pids).onEach(::record)
    }

    private fun record(states: Map<Int, SensorValueState>) {
        states.forEach { (pid, s) ->
            val live = s as? SensorValueState.Live ?: return@forEach
            if (lastSampleAt[pid] == live.at) return@forEach
            lastSampleAt[pid] = live.at
            history.update { h -> h + (pid to (h[pid].orEmpty() + live.value).takeLast(HISTORY_SIZE)) }
        }
    }

    val state: StateFlow<LiveDataUiState> = combine(
        visibleLayouts, tab, editing, states, combine(history, obd.state, notice) { h, o, n -> Triple(h, o, n) },
    ) { (id, all, active), t, e, s, (h, o, n) ->
        LiveDataUiState(id != null, all, active, t, e && t == WorkspaceTab.DASHBOARD, s, h, o, n)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveDataUiState())

    fun selectTab(t: WorkspaceTab) { tab.value = t }
    fun setEditing(on: Boolean) { editing.value = on }
    fun dismissNotice() { notice.value = null }

    fun selectLayout(id: String) = launchFor { vid -> dashboards.setActive(vid, id) }

    fun newLayout(name: String) = launchFor { vid ->
        val created = DashboardLayout(newId(), vid, DashboardLayout.DEFAULT_NAME)
        when (val r = created.rename(name)) {
            is LayoutResult.Changed -> {
                dashboards.save(r.layout)
                dashboards.setActive(r.layout.vehicleId, r.layout.id)
                editing.value = true
            }
            is LayoutResult.Rejected -> notice.value = message(r.error)
        }
    }

    fun renameLayout(name: String) = edit { it.rename(name) }

    fun deleteLayout() = viewModelScope.launch {
        val current = layouts.first().third ?: return@launch
        dashboards.delete(current.id)
        dashboards.setActive(current.vehicleId, null)
    }

    fun addGauge(pid: Int) {
        if (LiveSensorCatalog.find(pid) == null) return
        edit { it.add(GaugeSpec(pid, GaugeStyle.DIGITAL, GaugeSize.SMALL)) }
    }

    fun removeGauge(pid: Int) = edit { it.remove(pid) }
    fun moveGauge(from: Int, to: Int) = edit { it.move(from, to) }
    fun resizeGauge(pid: Int, size: GaugeSize) = edit { it.resize(pid, size) }
    fun restyleGauge(pid: Int, style: GaugeStyle) = edit { it.restyle(pid, style) }
    fun setGaugeUnit(pid: Int, unit: UnitChoice) = edit { it.setUnit(pid, unit) }

    private fun edit(change: (DashboardLayout) -> LayoutResult) = viewModelScope.launch {
        val current = layouts.first().third ?: return@launch
        when (val r = change(current)) {
            is LayoutResult.Changed -> {
                dashboards.save(r.layout)
                dashboards.setActive(r.layout.vehicleId, r.layout.id)
            }
            is LayoutResult.Rejected -> notice.value = message(r.error)
        }
    }

    private fun launchFor(block: suspend (String) -> Unit) {
        viewModelScope.launch {
            val vid = vehicles.activeVehicleId.first() ?: return@launch
            block(vid)
        }
    }

    private fun message(e: LayoutError) = when (e) {
        LayoutError.FULL -> "A dashboard holds at most ${DashboardLayout.MAX_GAUGES} gauges. Remove one to add another."
        LayoutError.DUPLICATE -> "That sensor is already on this dashboard."
        LayoutError.NOT_FOUND, LayoutError.BAD_POSITION -> "That change could not be made."
        LayoutError.BLANK_NAME -> "Give the layout a name."
    }

    private companion object {
        const val HISTORY_SIZE = 60
    }
}
