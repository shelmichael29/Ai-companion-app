package com.aicarcompanion.app

import android.content.Context
import com.aicarcompanion.app.data.local.AppDatabase
import com.aicarcompanion.app.data.local.RoomHistoryRepository
import com.aicarcompanion.app.data.local.RoomResponseCache
import com.aicarcompanion.app.data.local.RoomVehicleRepository
import com.aicarcompanion.app.data.local.SettingsStore
import com.aicarcompanion.app.data.local.SharedPreferencesSettingsStore
import com.aicarcompanion.app.data.sample.SampleDiagnosticsRepository
import com.aicarcompanion.app.data.vehicledb.FuelEconomyCatalogProvider
import com.aicarcompanion.app.data.vehicledb.NhtsaVinDecoder
import com.aicarcompanion.app.data.vehicledb.UrlConnectionHttpClient
import com.aicarcompanion.app.data.vehicledb.VehicleCatalogProvider
import com.aicarcompanion.app.data.vehicledb.VinDecoder
import com.aicarcompanion.app.diagnostics.obd.ObdConnection
import com.aicarcompanion.app.diagnostics.obd.UnavailableObdConnection
import com.aicarcompanion.app.domain.repository.DiagnosticsRepository
import com.aicarcompanion.app.domain.repository.MaintenanceRepository
import com.aicarcompanion.app.domain.repository.VehicleHistoryRepository
import com.aicarcompanion.app.domain.repository.VehicleRepository

/**
 * Manual dependency container. Swapping implementations (e.g. another vehicle database,
 * a different voice engine) happens here and nowhere else.
 */
interface AppContainer {
    val settings: SettingsStore
    val vehicleRepository: VehicleRepository
    val historyRepository: VehicleHistoryRepository
    val maintenanceRepository: MaintenanceRepository
    val diagnosticsRepository: DiagnosticsRepository
    val vehicleCatalog: VehicleCatalogProvider
    val vinDecoder: VinDecoder
    val obdConnection: ObdConnection
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val appContext = context.applicationContext
    private val database by lazy { AppDatabase.create(appContext) }
    private val http = UrlConnectionHttpClient()
    private val roomHistory by lazy { RoomHistoryRepository(database.serviceRecordDao(), database.diagnosticHistoryDao()) }

    override val settings: SettingsStore = SharedPreferencesSettingsStore(appContext)
    override val vehicleRepository: VehicleRepository by lazy { RoomVehicleRepository(database.vehicleDao(), settings) }
    override val historyRepository: VehicleHistoryRepository get() = roomHistory
    override val maintenanceRepository: MaintenanceRepository get() = roomHistory
    override val diagnosticsRepository: DiagnosticsRepository = SampleDiagnosticsRepository()
    override val vehicleCatalog: VehicleCatalogProvider by lazy {
        FuelEconomyCatalogProvider(http, RoomResponseCache(database.responseCacheDao()))
    }
    override val vinDecoder: VinDecoder = NhtsaVinDecoder(http)
    override val obdConnection: ObdConnection = UnavailableObdConnection()
}
