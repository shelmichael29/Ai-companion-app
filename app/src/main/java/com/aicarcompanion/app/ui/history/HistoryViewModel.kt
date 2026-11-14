package com.aicarcompanion.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DiagnosticObservation
import com.aicarcompanion.app.domain.model.MaintenanceRecord
import com.aicarcompanion.app.domain.model.MileageEntry
import com.aicarcompanion.app.domain.model.RecordedSensorReading
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.domain.repository.MaintenanceRepository
import com.aicarcompanion.app.domain.repository.VehicleHistoryRepository
import com.aicarcompanion.app.domain.repository.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class HistoryUiState(
    val vehicle: Vehicle? = null,
    val service: List<MaintenanceRecord> = emptyList(),
    val observations: List<DiagnosticObservation> = emptyList(),
    val codeReads: List<CodeReadSummary> = emptyList(),
    val readings: List<RecordedSensorReading> = emptyList(),
    val mileage: List<MileageEntry> = emptyList(),
    val loaded: Boolean = false,
)

/** History for the active vehicle only; each profile's history is stored separately. */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val vehicles: VehicleRepository,
    private val history: VehicleHistoryRepository,
    private val maintenance: MaintenanceRepository,
) : ViewModel() {

    val state: StateFlow<HistoryUiState> = vehicles.activeVehicleId.flatMapLatest { id ->
        if (id == null) flowOf(HistoryUiState(loaded = true)) else combine(
            vehicles.observeVehicle(id),
            maintenance.history(id),
            history.observations(id),
            combine(history.troubleCodeHistory(id), history.sensorHistory(id, 50), history.mileage(id)) { a, b, c -> Triple(a, b, c) },
        ) { vehicle, service, obs, (codes, readings, miles) ->
            HistoryUiState(
                vehicle = vehicle,
                service = service,
                observations = obs,
                codeReads = codes.groupBy { it.readSessionId }.values
                    .map { group -> CodeReadSummary(group.first().readAt, group.first().origin, group.map { it.code to it.status }) }
                    .sortedByDescending { it.readAt },
                readings = readings,
                mileage = miles,
                loaded = true,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    /** Returns an error message, or null on success. */
    suspend fun addService(form: ServiceForm): String? {
        val vehicle = state.value.vehicle ?: return "Add a vehicle first."
        val record = form.validate(UUID.randomUUID().toString(), vehicle.id, LocalDate.now()).getOrElse { return it.message }
        maintenance.save(record)
        record.odometerMiles?.let { raiseOdometer(vehicle, it) }
        return null
    }

    suspend fun addObservation(form: ObservationForm): String? {
        val vehicle = state.value.vehicle ?: return "Add a vehicle first."
        val obs = form.validate(UUID.randomUUID().toString(), vehicle.id, Instant.now()).getOrElse { return it.message }
        history.saveObservation(obs)
        return null
    }

    suspend fun addMileage(text: String): String? {
        val vehicle = state.value.vehicle ?: return "Add a vehicle first."
        val miles = text.replace(",", "").trim().toIntOrNull()?.takeIf { it in 0..2_000_000 }
            ?: return "Enter whole miles."
        history.recordMileage(MileageEntry(0, vehicle.id, miles, Instant.now(), DataOrigin.USER_ENTERED))
        raiseOdometer(vehicle, miles)
        return null
    }

    fun deleteService(id: String) = viewModelScope.launch { maintenance.delete(id) }
    fun deleteObservation(id: String) = viewModelScope.launch { history.deleteObservation(id) }

    /** Keeps the profile's odometer at the highest known reading. */
    private suspend fun raiseOdometer(vehicle: Vehicle, miles: Int) {
        val current = vehicles.observeVehicle(vehicle.id).first() ?: return
        if ((current.odometerMiles ?: 0) < miles) vehicles.upsert(current.copy(odometerMiles = miles))
    }
}
