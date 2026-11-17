package com.aicarcompanion.app.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicarcompanion.app.data.photos.VehiclePhotoStore
import com.aicarcompanion.app.diagnostics.obd.ObdConnection
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DiagnosticTroubleCode
import com.aicarcompanion.app.domain.model.DtcSeverity
import com.aicarcompanion.app.domain.model.TroubleCodeRead
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.domain.repository.DiagnosticsRepository
import com.aicarcompanion.app.domain.repository.VehicleHistoryRepository
import com.aicarcompanion.app.domain.repository.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime

data class HomeUiState(
    val vehicle: Vehicle? = null,
    val vehicles: List<Vehicle> = emptyList(),
    val obdState: ObdConnectionState = ObdConnectionState.Disconnected,
    val codes: List<DiagnosticTroubleCode> = emptyList(),
    val lastRead: TroubleCodeRead? = null,
    val loaded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val vehicles: VehicleRepository,
    diagnostics: DiagnosticsRepository,
    history: VehicleHistoryRepository,
    obd: ObdConnection,
    val photos: VehiclePhotoStore,
) : ViewModel() {

    val state: StateFlow<HomeUiState> = vehicles.activeVehicleId.flatMapLatest { id ->
        if (id == null) {
            combine(vehicles.vehicles, obd.state) { all, o -> HomeUiState(null, all, o, loaded = true) }
        } else {
            combine(
                vehicles.vehicles,
                vehicles.observeVehicle(id),
                diagnostics.troubleCodes(id),
                history.latestTroubleCodeRead(id),
                obd.state,
            ) { all, v, codes, read, o -> HomeUiState(v, all, o, codes, read, loaded = true) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    val photoVersions = photos.versions

    fun select(id: String) = viewModelScope.launch { vehicles.setActive(id) }
    fun savePhoto(vehicleId: String, uri: Uri) = viewModelScope.launch { photos.save(vehicleId, uri) }
}

/** Friendly greeting by time of day. */
fun greetingFor(time: LocalTime): String = when (time.hour) {
    in 5..11 -> "Good morning!"
    in 12..16 -> "Good afternoon!"
    in 17..21 -> "Good evening!"
    else -> "Hi there!"
}

/** One-line car health summary for the home screen. Never claims "all good" without a real read. */
data class HealthSummary(val title: String, val subtitle: String, val hasIssues: Boolean)

fun healthSummary(codes: List<DiagnosticTroubleCode>, lastRead: TroubleCodeRead?): HealthSummary {
    val sample = codes.any { it.origin == DataOrigin.SAMPLE }
    return when {
        codes.isNotEmpty() -> {
            val important = codes.any { it.severity == DtcSeverity.SEVERE }
            HealthSummary(
                if (codes.size == 1) "1 thing to look at" else "${codes.size} things to look at",
                (if (important) "One needs attention soon. " else "") + if (sample) "Sample data, not from your car." else "Tap to see what it means.",
                hasIssues = true,
            )
        }
        lastRead != null -> HealthSummary("No warning codes found", "Last checked with your adapter. Tap for details.", hasIssues = false)
        else -> HealthSummary("Car health not checked yet", "Connect an OBD-II adapter and I can check for warning codes.", hasIssues = false)
    }
}

/** Connection wording: "Connected" only when the adapter link is confirmed. */
fun connectionSummary(state: ObdConnectionState): Pair<String, Boolean> {
    val stage = com.aicarcompanion.app.diagnostics.obd.VehicleLinkStage.of(state)
    return when (stage) {
        com.aicarcompanion.app.diagnostics.obd.VehicleLinkStage.VEHICLE_CONNECTED -> "Connected to your car"
        com.aicarcompanion.app.diagnostics.obd.VehicleLinkStage.VEHICLE_PENDING -> "Adapter connected · car not responding"
        com.aicarcompanion.app.diagnostics.obd.VehicleLinkStage.DEGRADED,
        com.aicarcompanion.app.diagnostics.obd.VehicleLinkStage.RECOVERING -> "Reconnecting to your car…"
        com.aicarcompanion.app.diagnostics.obd.VehicleLinkStage.FAILED, com.aicarcompanion.app.diagnostics.obd.VehicleLinkStage.ADAPTER_NOT_FOUND,
        com.aicarcompanion.app.diagnostics.obd.VehicleLinkStage.ADAPTER_DISCOVERED, com.aicarcompanion.app.diagnostics.obd.VehicleLinkStage.PAIRING_REQUIRED ->
            if (state is ObdConnectionState.Connecting) "Connecting…" else "Not connected"
        else -> "Connecting…"
    } to (state is ObdConnectionState.Connected)
}
