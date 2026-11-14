package com.aicarcompanion.app.data.sample

import com.aicarcompanion.app.domain.model.DiagnosticTroubleCode
import com.aicarcompanion.app.domain.model.MaintenanceRecord
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.domain.repository.DiagnosticsRepository
import com.aicarcompanion.app.domain.repository.MaintenanceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Serves fixed [SampleData]. All results are tagged as sample data, never as vehicle data. */
class SampleDiagnosticsRepository : DiagnosticsRepository {
    override fun troubleCodes(vehicleId: String): Flow<List<DiagnosticTroubleCode>> =
        flowOf(SampleData.troubleCodes())

    override fun sensorReadings(vehicleId: String): Flow<List<SensorReading>> =
        flowOf(SampleData.sensorReadings())
}

class SampleMaintenanceRepository : MaintenanceRepository {
    override fun history(vehicleId: String): Flow<List<MaintenanceRecord>> =
        flowOf(SampleData.maintenance(vehicleId))
}
