package com.aicarcompanion.app.domain.repository

import com.aicarcompanion.app.domain.model.DiagnosticTroubleCode
import com.aicarcompanion.app.domain.model.MaintenanceRecord
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.domain.model.Vehicle
import kotlinx.coroutines.flow.Flow

/**
 * Repository interfaces are the seam between UI and data. Today they are backed by in-memory
 * sample implementations; later they will be backed by Room (profiles, history) and the OBD-II
 * layer (live diagnostics) without changing any screen code.
 */
interface VehicleRepository {
    val vehicles: Flow<List<Vehicle>>
    val activeVehicleId: Flow<String?>

    fun observeVehicle(id: String): Flow<Vehicle?>
    suspend fun upsert(vehicle: Vehicle)
    suspend fun delete(id: String)
    suspend fun setActive(id: String)
}

interface DiagnosticsRepository {
    fun troubleCodes(vehicleId: String): Flow<List<DiagnosticTroubleCode>>
    fun sensorReadings(vehicleId: String): Flow<List<SensorReading>>
}

interface MaintenanceRepository {
    fun history(vehicleId: String): Flow<List<MaintenanceRecord>>
}
