package com.aicarcompanion.app

import android.content.Context
import com.aicarcompanion.app.assistant.VoiceSession
import com.aicarcompanion.app.assistant.conversation.CommandParser
import com.aicarcompanion.app.assistant.conversation.ConversationManager
import com.aicarcompanion.app.assistant.conversation.DiagnosticCommandHandler
import com.aicarcompanion.app.assistant.conversation.RuleBasedReasoningProvider
import com.aicarcompanion.app.assistant.conversation.VehicleContext
import com.aicarcompanion.app.assistant.conversation.VehicleContextProvider
import com.aicarcompanion.app.assistant.voice.AndroidSpeechInput
import com.aicarcompanion.app.assistant.voice.AndroidAudioFocus
import com.aicarcompanion.app.assistant.voice.AndroidSpeechOutput
import com.aicarcompanion.app.assistant.voice.SpeechOutput
import com.aicarcompanion.app.assistant.voice.VoiceConfig
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
import com.aicarcompanion.app.diagnostics.bluetooth.BluetoothAvailability
import com.aicarcompanion.app.diagnostics.bluetooth.BluetoothGateway
import com.aicarcompanion.app.diagnostics.obd.ObdConnection
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdManager
import com.aicarcompanion.app.diagnostics.obd.ObdConfigStore
import com.aicarcompanion.app.diagnostics.obd.ObdAdvancedControls
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionConfig
import com.aicarcompanion.app.data.local.SharedPreferencesKeyValueStore
import kotlinx.coroutines.flow.combine
import com.aicarcompanion.app.domain.repository.DiagnosticsRepository
import com.aicarcompanion.app.domain.repository.MaintenanceRepository
import com.aicarcompanion.app.domain.repository.VehicleHistoryRepository
import com.aicarcompanion.app.domain.repository.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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
    val diagnosticCommands: DiagnosticCommandHandler
    val bluetooth: BluetoothGateway
    /** Saved OBD-II connection settings (applied only at the Advanced level). */
    val obdConfig: ObdConfigStore
    val obdControls: ObdAdvancedControls
    val voiceSession: VoiceSession
    val speechOutput: SpeechOutput
    val vehiclePhotos: com.aicarcompanion.app.data.photos.VehiclePhotoStore

    /** Called when the app leaves the foreground: the microphone must never stay on in the background. */
    fun onAppBackgrounded()

    /** Reconnects to the last-used adapter if Bluetooth is ready. Never prompts the user. */
    fun autoConnectAdapter()
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
    override val bluetooth = BluetoothGateway(appContext)
    override val obdConfig = ObdConfigStore(
        SharedPreferencesKeyValueStore(
            appContext.getSharedPreferences("obd_settings", Context.MODE_PRIVATE),
        ),
    )
    private val obdManager by lazy {
        ObdManager(appScope, bluetooth, historyRepository, config = {
            ObdConnectionConfig.effectiveFor(
                settings.settings.value.explanationLevel, obdConfig.config.value,
            )
        })
    }
    override val obdControls: ObdAdvancedControls get() = obdManager
    override val obdConnection: ObdConnection get() = obdManager
    override val diagnosticCommands: DiagnosticCommandHandler get() = obdManager

    override fun autoConnectAdapter() {
        val address = settings.settings.value.lastAdapterAddress ?: return
        if (obdManager.state.value != ObdConnectionState.Disconnected) return
        if (bluetooth.availability() != BluetoothAvailability.READY) return
        bluetooth.findPaired(address)?.let(obdManager::connect)
    }

    override val diagnosticsRepository: DiagnosticsRepository by lazy {
        DefaultDiagnosticsRepository(historyRepository, settings, GenericDtcCatalog, obdManager.liveReadings)
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

    override val vehiclePhotos by lazy { com.aicarcompanion.app.data.photos.VehiclePhotoStore(appContext) }
    private val audioFocus by lazy { AndroidAudioFocus(appContext) }

    override val speechOutput: SpeechOutput by lazy {
        AndroidSpeechOutput(appContext, audioFocus, audioFocus.attributes).also { output ->
            appScope.launch {
                settings.settings
                    .map { VoiceConfig(it.speechRate, it.speechPitch, it.ttsEngine, it.ttsVoice) }
                    .distinctUntilChanged()
                    .collect(output::configure)
            }
        }
    }

    private val voiceSessionLazy = lazy {
        val assistantName = { settings.settings.value.assistantName }
        val reasoning = RuleBasedReasoningProvider(
            CommandParser(assistantName), vehicleContext, diagnosticCommands, GenericDtcCatalog,
            recorder = { historyRepository.saveObservation(it) },
        )
        VoiceSession(
            scope = appScope,
            input = AndroidSpeechInput(appContext),
            output = speechOutput,
            conversation = ConversationManager(reasoning, assistantName, { settings.settings.value.explanationLevel }),
            focus = audioFocus,
            continuousEnabled = { settings.settings.value.continuousConversation },
        )
    }
    override val voiceSession: VoiceSession by voiceSessionLazy

    override fun onAppBackgrounded() {
        if (voiceSessionLazy.isInitialized()) voiceSession.onAppBackgrounded()
    }

    init {
        // Live polling covers only the readings the user's detail level shows.
        appScope.launch {
            settings.settings.map { it.explanationLevel }.distinctUntilChanged().collect { obdConnection.setLiveDetail(it) }
        }
        // Re-apply connection settings when they, or the level that decides whether they apply, change.
        appScope.launch {
            combine(settings.settings.map { it.explanationLevel }, obdConfig.config) { level, cfg ->
                ObdConnectionConfig.effectiveFor(level, cfg)
            }.distinctUntilChanged().collect { obdManager.applyConfig() }
        }
    }
}
