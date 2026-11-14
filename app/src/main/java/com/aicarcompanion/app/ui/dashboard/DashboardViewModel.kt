package com.aicarcompanion.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicarcompanion.app.diagnostics.obd.ObdConnection
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DiagnosticTroubleCode
import com.aicarcompanion.app.domain.model.MaintenanceRecord
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.domain.repository.DiagnosticsRepository
import com.aicarcompanion.app.domain.repository.MaintenanceRepository
import com.aicarcompanion.app.domain.repository.VehicleRepository
import com.aicarcompanion.app.domain.repository.VehicleHistoryRepository
import com.aicarcompanion.app.domain.model.TroubleCodeRead
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

sealed interface DashboardUiState {
    data object Loading : DashboardUiState
    data object NoVehicle : DashboardUiState
    data class Ready(
        val vehicle: Vehicle,
        val troubleCodes: List<DiagnosticTroubleCode>,
        val sensorReadings: List<SensorReading>,
        val maintenance: List<MaintenanceRecord>,
        val obdState: ObdConnectionState,
        /** Most recent saved code read (null if codes were never read for this vehicle). */
        val lastCodeRead: TroubleCodeRead? = null,
    ) : DashboardUiState {
        /** True if anything shown is demonstration data rather than real data. */
        val containsSampleData: Boolean
            get() = troubleCodes.any { it.origin == DataOrigin.SAMPLE } ||
                sensorReadings.any { it.origin == DataOrigin.SAMPLE } ||
                maintenance.any { it.origin == DataOrigin.SAMPLE }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(
    vehicleRepository: VehicleRepository,
    diagnosticsRepository: DiagnosticsRepository,
    maintenanceRepository: MaintenanceRepository,
    historyRepository: VehicleHistoryRepository,
    obdConnection: ObdConnection,
) : ViewModel() {

    val uiState: StateFlow<DashboardUiState> = vehicleRepository.activeVehicleId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(DashboardUiState.NoVehicle)
            } else {
                combine(
                    vehicleRepository.observeVehicle(id),
                    diagnosticsRepository.troubleCodes(id),
                    diagnosticsRepository.sensorReadings(id),
                    maintenanceRepository.history(id),
                    combine(obdConnection.state, historyRepository.latestTroubleCodeRead(id)) { o, r -> o to r },
                ) { vehicle, codes, readings, history, (obd, lastRead) ->
                    if (vehicle == null) {
                        DashboardUiState.NoVehicle
                    } else {
                        DashboardUiState.Ready(
                            vehicle, codes, readings, history.sortedByDescending { it.date }, obd, lastRead,
                        )
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState.Loading)
}
