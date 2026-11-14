package com.aicarcompanion.app

import com.aicarcompanion.app.data.memory.InMemoryVehicleRepository
import com.aicarcompanion.app.data.sample.SampleData
import com.aicarcompanion.app.data.sample.SampleDiagnosticsRepository
import com.aicarcompanion.app.data.sample.SampleMaintenanceRepository
import com.aicarcompanion.app.diagnostics.obd.ObdConnection
import com.aicarcompanion.app.diagnostics.obd.UnavailableObdConnection
import com.aicarcompanion.app.domain.repository.DiagnosticsRepository
import com.aicarcompanion.app.domain.repository.MaintenanceRepository
import com.aicarcompanion.app.domain.repository.VehicleRepository

/**
 * Manual dependency container. Swapping sample implementations for real ones (Room, OBD-II)
 * happens here and nowhere else. A DI framework can be introduced if this grows unwieldy.
 */
interface AppContainer {
    val vehicleRepository: VehicleRepository
    val diagnosticsRepository: DiagnosticsRepository
    val maintenanceRepository: MaintenanceRepository
    val obdConnection: ObdConnection
}

class DefaultAppContainer : AppContainer {
    override val vehicleRepository: VehicleRepository = InMemoryVehicleRepository(SampleData.vehicles)
    override val diagnosticsRepository: DiagnosticsRepository = SampleDiagnosticsRepository()
    override val maintenanceRepository: MaintenanceRepository = SampleMaintenanceRepository()
    override val obdConnection: ObdConnection = UnavailableObdConnection()
}
