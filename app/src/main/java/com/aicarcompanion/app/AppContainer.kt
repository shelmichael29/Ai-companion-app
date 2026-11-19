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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    /** Beginner-friendly adapter search and connection with bounded retries. */
    val adapterSetup: com.aicarcompanion.app.connectivity.AdapterConnectionManager
    /** Saved OBD-II connection settings (applied only at the Advanced level). */
    val obdConfig: ObdConfigStore
    val obdControls: ObdAdvancedControls
    val voiceSession: VoiceSession
    val speechOutput: SpeechOutput
    val vehiclePhotos: com.aicarcompanion.app.data.photos.VehiclePhotoStore

    /** Structured, redacted diagnostics for the vehicle, internet and AI connections (in memory only). */
    val connectionLog: com.aicarcompanion.app.connectivity.ConnectionLog
    /** Validated internet availability from Android's default network. */
    val internet: com.aicarcompanion.app.connectivity.InternetMonitor

    /** Gemini availability, for Settings and the chat screen. */
    val aiStatus: kotlinx.coroutines.flow.StateFlow<com.aicarcompanion.app.ai.AiStatus>

    /** Where Penelope's latest answer came from (Gemini, built-in safety, fallback and why). */
    val answerSources: com.aicarcompanion.app.ai.ResponseSourceTracker
    val aiModelName: String
    val aiAppCheckProvider: String

    /** Sets up App Check and Remote Config when Firebase is configured. Safe otherwise. */
    fun initializeAi()

    /** Called when the app leaves the foreground: the microphone must never stay on in the background. */
    fun onAppBackgrounded()

    /** The app became visible again (diagnostics only). */
    fun onAppForegrounded() {}

    /** Which hands-free features this build and platform can offer. */
    val handsFreeCapabilities: com.aicarcompanion.app.handsfree.CapabilityProvider

    /** Where Penelope's voice plays (car Bluetooth, phone speaker…), for diagnostics. */
    val carAudioRoute: com.aicarcompanion.app.handsfree.CarAudioRoute

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
    override val connectionLog = com.aicarcompanion.app.connectivity.ConnectionLog()
    override val answerSources = com.aicarcompanion.app.ai.ResponseSourceTracker(connectionLog)
    override val internet: com.aicarcompanion.app.connectivity.InternetMonitor by lazy {
        com.aicarcompanion.app.connectivity.AndroidInternetMonitor(appContext, connectionLog)
    }
    private val internetOnline by lazy {
        internet.state.map { it.online }.stateIn(appScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, internet.state.value.online)
    }
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
    /** The selected car, kept current for protocol choices. Its VIN is never sent to the adapter. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val activeVehicle by lazy {
        vehicleRepository.activeVehicleId
            .flatMapLatest { id -> id?.let { vehicleRepository.observeVehicle(it) } ?: kotlinx.coroutines.flow.flowOf(null) }
            .stateIn(appScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    }

    private val obdManager by lazy {
        ObdManager(
            appScope, bluetooth, historyRepository,
            config = { ObdConnectionConfig.effectiveFor(settings.settings.value.explanationLevel, obdConfig.config.value) },
            activeVehicle = { activeVehicle.value },
            protocolMemory = com.aicarcompanion.app.diagnostics.obd.SharedPreferencesProtocolMemory(
                appContext.getSharedPreferences("obd_settings", Context.MODE_PRIVATE),
            ),
            log = connectionLog,
        )
    }
    override val obdControls: ObdAdvancedControls get() = obdManager
    override val obdConnection: ObdConnection get() = obdManager
    override val diagnosticCommands: DiagnosticCommandHandler get() = obdManager

    override val adapterSetup by lazy {
        com.aicarcompanion.app.connectivity.AdapterConnectionManager(
            scope = appScope,
            transport = bluetooth,
            obd = obdManager,
            lastAdapterAddress = { settings.settings.value.lastAdapterAddress },
            rememberAdapter = { device -> settings.update { it.copy(lastAdapterAddress = device.address) } },
            log = connectionLog,
        )
    }

    /** Reconnects quietly to the adapter that last worked; never searches or prompts. */
    override fun autoConnectAdapter() {
        adapterSetup.reconnectLastAdapter()
    }

    /** Connection changes Penelope may mention, spoken only between conversation turns. */
    private val announcer by lazy {
        com.aicarcompanion.app.connectivity.ConnectionAnnouncer(appScope) { text ->
            if (voiceSessionLazy.isInitialized()) voiceSession.queueStatusNotice(text)
        }
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
            recentSymptoms = id?.let { vid ->
                historyRepository.observations(vid).first()
                    .filter { it.kind == com.aicarcompanion.app.domain.model.ObservationKind.SYMPTOM }
                    .map { it.text }.take(3)
            }.orEmpty(),
            obdState = obdConnection.state.value,
            demoCodes = if (settings.settings.value.demoDataEnabled) SampleData.troubleCodes() else emptyList(),
        )
    }

    override val vehiclePhotos by lazy { com.aicarcompanion.app.data.photos.VehiclePhotoStore(appContext) }
    private val audioFocus by lazy { AndroidAudioFocus(appContext, connectionLog) }

    /** Hands-free foundation (Phase H0): capabilities report "not yet"; see docs/HANDS_FREE.md. */
    override val handsFreeCapabilities: com.aicarcompanion.app.handsfree.CapabilityProvider =
        com.aicarcompanion.app.handsfree.android.AndroidHandsFreeCapabilities()
    override val carAudioRoute: com.aicarcompanion.app.handsfree.CarAudioRoute by lazy {
        com.aicarcompanion.app.handsfree.android.AndroidCarAudioRoute(appContext, connectionLog)
    }

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
        val parser = CommandParser(assistantName)
        val recorder = com.aicarcompanion.app.assistant.conversation.ObservationRecorder { historyRepository.saveObservation(it) }
        val ruleBased = RuleBasedReasoningProvider(parser, vehicleContext, diagnosticCommands, GenericDtcCatalog, recorder)
        // Hands-free commands (music, messages, calls) are routed first, so the AI never sees
        // names or message text and never pretends to perform them.
        val reasoning = com.aicarcompanion.app.handsfree.HandsFreeReasoningProvider(
            delegate = geminiProvider(parser, ruleBased, recorder),
            capabilities = handsFreeCapabilities,
        ) { kind, status ->
            connectionLog.record(com.aicarcompanion.app.connectivity.LogArea.APP, "Hands-free command", mapOf("command" to kind, "status" to status))
            answerSources.record(com.aicarcompanion.app.ai.ResponseRecord(com.aicarcompanion.app.ai.ResponseSource.DEVICE_COMMAND, category = status))
        }
        VoiceSession(
            scope = appScope,
            input = AndroidSpeechInput(appContext).apply { preferOffline = { !internetOnline.value } },
            output = speechOutput,
            conversation = ConversationManager(reasoning, assistantName, { settings.settings.value.explanationLevel }),
            focus = audioFocus,
            continuousEnabled = { settings.settings.value.continuousConversation },
            internetOnline = internetOnline,
        ).also { session ->
            com.aicarcompanion.app.connectivity.VoiceDiagnostics(appScope, connectionLog).watch(session.state)
            carAudioRoute // start watching the audio route once voice is in use
        }
    }
    override val voiceSession: VoiceSession by voiceSessionLazy

    private val aiClient by lazy { com.aicarcompanion.app.ai.FirebaseAiChatClient(appContext) }
    private var gemini: com.aicarcompanion.app.ai.GeminiReasoningProvider? = null
    private val fallbackAiStatus = kotlinx.coroutines.flow.MutableStateFlow<com.aicarcompanion.app.ai.AiStatus>(com.aicarcompanion.app.ai.AiStatus.NotConfigured)

    private fun geminiProvider(
        parser: CommandParser,
        ruleBased: RuleBasedReasoningProvider,
        recorder: com.aicarcompanion.app.assistant.conversation.ObservationRecorder,
    ) = com.aicarcompanion.app.ai.GeminiReasoningProvider(
        parser = parser,
        deterministic = ruleBased,
        context = vehicleContext,
        client = aiClient,
        knowledge = GenericDtcCatalog,
        aiEnabled = { settings.settings.value.aiEnabled },
        recorder = recorder,
        internetAvailable = { internetOnline.value },
        log = connectionLog,
        aiTimeoutMillis = { aiClient.requestTimeoutMillis },
        sources = answerSources,
    ).also { provider ->
        gemini = provider
        appScope.launch { settings.settings.map { it.aiEnabled }.distinctUntilChanged().collect { provider.refreshStatus() } }
    }

    override val aiStatus: kotlinx.coroutines.flow.StateFlow<com.aicarcompanion.app.ai.AiStatus>
        get() { voiceSession; return gemini?.status ?: fallbackAiStatus }
    override val aiModelName: String get() = aiClient.modelName
    override val aiAppCheckProvider: String get() = com.aicarcompanion.app.ai.AppCheckInstaller.PROVIDER_NAME
    override fun initializeAi() = aiClient.initialize()

    override fun onAppBackgrounded() {
        connectionLog.record(com.aicarcompanion.app.connectivity.LogArea.APP, "App in background")
        if (voiceSessionLazy.isInitialized()) voiceSession.onAppBackgrounded()
    }

    override fun onAppForegrounded() {
        connectionLog.record(com.aicarcompanion.app.connectivity.LogArea.APP, "App in foreground")
    }

    init {
        announcer.watch(obdManager.state)
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
