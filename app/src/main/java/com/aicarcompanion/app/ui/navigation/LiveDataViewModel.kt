package com.aicarcompanion.app.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicarcompanion.app.diagnostics.obd.ObdConnection
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.domain.repository.DiagnosticsRepository
import com.aicarcompanion.app.domain.repository.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

data class LiveDataUiState(
    val hasVehicle: Boolean = false,
    val readings: List<SensorReading> = emptyList(),
    val obdState: ObdConnectionState = ObdConnectionState.Disconnected,
)

/** Readings for the active vehicle. Collected only while the Live Data screen is visible. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveDataViewModel(
    vehicles: VehicleRepository,
    diagnostics: DiagnosticsRepository,
    obd: ObdConnection,
) : ViewModel() {
    val state: StateFlow<LiveDataUiState> = vehicles.activeVehicleId.flatMapLatest { id ->
        val readings = if (id == null) flowOf(emptyList()) else diagnostics.sensorReadings(id)
        combine(readings, obd.state) { r, o -> LiveDataUiState(id != null, r, o) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveDataUiState())
}
