package com.aicarcompanion.app

import android.content.Context
import com.aicarcompanion.app.assistant.VoiceSession
import com.aicarcompanion.app.assistant.conversation.CommandParser
import com.aicarcompanion.app.assistant.conversation.ConversationManager
import com.aicarcompanion.app.assistant.conversation.DiagnosticCommandHandler
import com.aicarcompanion.app.assistant.conversation.NoAdapterDiagnosticCommandHandler
import com.aicarcompanion.app.assistant.conversation.RuleBasedReasoningProvider
import com.aicarcompanion.app.assistant.conversation.VehicleContext
import com.aicarcompanion.app.assistant.conversation.VehicleContextProvider
import com.aicarcompanion.app.assistant.voice.AndroidSpeechInput
import com.aicarcompanion.app.assistant.voice.AndroidSpeechOutput
import com.aicarcompanion.app.data.DefaultDiagnosticsRepository
import com.aicarcompanion.app.data.local.AppDatabase
import com.aicarcompanion.app.data.local.RoomHistoryRepository
import com.aicarcompanion.app.data.local.RoomResponseCache
import com.aicarcompanion.app.data.local.RoomVehicleRepository
import com.aicarcompanion.app.data.local.SettingsStore
import com.aicarcompanion.app.data.local.SharedPreferencesSettingsStore
import com.aicarcompanion.app.data.sample.SampleData
import com.aicarcompanion.app.data.vehicledb.FuelEconomyCatalogProvider
import com.aicarcompanion.app.data.vehicledb.NhtsaVinDecoder
import com.aicarcompanion.app.data.vehicledb.UrlConnectionHttpClient
import com.aicarcompanion.app.data.vehicledb.VehicleCatalogProvider
import com.aicarcompanion.app.data.vehicledb.VinDecoder
import com.aicarcompanion.app.diagnostics.dtc.GenericDtcCatalog
import com.aicarcompanion.app.diagnostics.obd.ObdConnection
import com.aicarcompanion.app.diagnostics.obd.UnavailableObdConnection
import com.aicarcompanion.app.domain.repository.DiagnosticsRepository
import com.aicarcompanion.app.domain.repository.MaintenanceRepository
import com.aicarcompanion.app.domain.repository.VehicleHistoryRepository
import com.aicarcompanion.app.domain.repository.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Manual dependency container. Swapping implementations (another vehicle database, a cloud
 * voice, an AI reasoning provider) happens here and nowhere else.
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
    val voiceSession: VoiceSession
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val appContext = context.applicationContext
    /** Lives as long as the app process; used for work that outlives a single screen. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val database by lazy { AppDatabase.create(appContext) }
    private val http = UrlConnectionHttpClient()
    private val roomHistory by lazy { RoomHistoryRepository(database.serviceRecordDao(), database.diagnosticHistoryDao()) }

    override val settings: SettingsStore = SharedPreferencesSettingsStore(appContext)
    override val vehicleRepository: VehicleRepository by lazy { RoomVehicleRepository(database.vehicleDao(), settings) }
    override val historyRepository: VehicleHistoryRepository get() = roomHistory
    override val maintenanceRepository: MaintenanceRepository get() = roomHistory
    override val vehicleCatalog: VehicleCatalogProvider by lazy {
        FuelEconomyCatalogProvider(http, RoomResponseCache(database.responseCacheDao()))
    }
    override val vinDecoder: VinDecoder = NhtsaVinDecoder(http)
    override val obdConnection: ObdConnection = UnavailableObdConnection()
    private val diagnosticCommands: DiagnosticCommandHandler = NoAdapterDiagnosticCommandHandler

    override val diagnosticsRepository: DiagnosticsRepository by lazy {
        DefaultDiagnosticsRepository(historyRepository, settings, GenericDtcCatalog, flowOf(emptyList()))
    }

    private val vehicleContext = VehicleContextProvider {
        val id = vehicleRepository.activeVehicleId.first()
        VehicleContext(
            vehicle = id?.let { vehicleRepository.observeVehicle(it).first() },
            latestCodeRead = id?.let { historyRepository.latestTroubleCodeRead(it).first() },
            maintenance = id?.let { maintenanceRepository.history(it).first() }.orEmpty(),
            obdState = obdConnection.state.value,
            demoCodes = if (settings.settings.value.demoDataEnabled) SampleData.troubleCodes() else emptyList(),
        )
    }

    override val voiceSession: VoiceSession by lazy {
        val assistantName = { settings.settings.value.assistantName }
        val reasoning = RuleBasedReasoningProvider(
            CommandParser(assistantName), vehicleContext, diagnosticCommands, GenericDtcCatalog,
        )
        val output = AndroidSpeechOutput(appContext)
        appScope.launch {
            settings.settings.map { it.speechRate }.distinctUntilChanged().collect(output::setRate)
        }
        VoiceSession(appScope, AndroidSpeechInput(appContext), output, ConversationManager(reasoning, assistantName))
    }
}
