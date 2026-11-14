package com.aicarcompanion.app.domain.repository

import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DiagnosticObservation
import com.aicarcompanion.app.domain.model.DiagnosticTroubleCode
import com.aicarcompanion.app.domain.model.DtcStatus
import com.aicarcompanion.app.domain.model.MaintenanceRecord
import com.aicarcompanion.app.domain.model.MileageEntry
import com.aicarcompanion.app.domain.model.RecordedSensorReading
import com.aicarcompanion.app.domain.model.RecordedTroubleCode
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.domain.model.TroubleCodeRead
import com.aicarcompanion.app.domain.model.Vehicle
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Repository interfaces are the seam between UI and data. Implementations are chosen in
 * [com.aicarcompanion.app.AppContainer] (Room on-device, in-memory for tests).
 */
interface VehicleRepository {
    val vehicles: Flow<List<Vehicle>>
    val activeVehicleId: Flow<String?>

    fun observeVehicle(id: String): Flow<Vehicle?>
    suspend fun upsert(vehicle: Vehicle)
    suspend fun delete(id: String)
    suspend fun setActive(id: String)
}

/** What the dashboard and Penelope show right now: latest codes and current sensor values. */
interface DiagnosticsRepository {
    fun troubleCodes(vehicleId: String): Flow<List<DiagnosticTroubleCode>>
    fun sensorReadings(vehicleId: String): Flow<List<SensorReading>>
}

interface MaintenanceRepository {
    fun history(vehicleId: String): Flow<List<MaintenanceRecord>>
    suspend fun save(record: MaintenanceRecord) {}
    suspend fun delete(id: String) {}
}

/** Long-term, per-vehicle diagnostic memory. */
interface VehicleHistoryRepository {
    /** The most recent trouble-code read for the vehicle, or null if codes were never read. */
    fun latestTroubleCodeRead(vehicleId: String): Flow<TroubleCodeRead?>
    fun troubleCodeHistory(vehicleId: String): Flow<List<RecordedTroubleCode>>
    suspend fun recordTroubleCodes(
        vehicleId: String,
        codes: List<Pair<String, DtcStatus>>,
        origin: DataOrigin,
        readAt: Instant,
    )

    fun sensorHistory(vehicleId: String, limit: Int = 200): Flow<List<RecordedSensorReading>>
    suspend fun recordSensorReadings(readings: List<RecordedSensorReading>)

    fun observations(vehicleId: String): Flow<List<DiagnosticObservation>>
    suspend fun saveObservation(observation: DiagnosticObservation)
    suspend fun deleteObservation(id: String)

    fun mileage(vehicleId: String): Flow<List<MileageEntry>>
    suspend fun recordMileage(entry: MileageEntry)
}
