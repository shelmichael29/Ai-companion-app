package com.aicarcompanion.app.ui.home

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import com.aicarcompanion.app.ai.AiFailure
import com.aicarcompanion.app.ai.AiStatus
import com.aicarcompanion.app.dashboard.DashboardItem
import com.aicarcompanion.app.dashboard.DashboardKind
import com.aicarcompanion.app.dashboard.DashboardPlanner
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.aicarcompanion.app.data.photos.rememberVehiclePhoto
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.ui.AppViewModelProvider
import com.aicarcompanion.app.ui.assistant.AssistantViewModel
import com.aicarcompanion.app.ui.assistant.openAppSettings
import com.aicarcompanion.app.ui.assistant.rememberMicAction
import com.aicarcompanion.app.ui.theme.Penelope
import java.time.LocalTime

val HOME_SUGGESTIONS = listOf(
    "My car is shaking.",
    "What does this warning light mean?",
    "Can I still drive my car?",
    "Help me understand a repair.",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    onOpenChat: () -> Unit,
    onOpenGarage: () -> Unit,
    onOpenAdapter: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenResults: () -> Unit,
    onAddVehicle: () -> Unit,
    onOpenHistory: () -> Unit = {},
    onOpenExtras: () -> Unit = {},
    onOpenConnectionSettings: () -> Unit = {},
    onOpenDiagnostics: (() -> Unit)? = null,
    onOpenLiveData: (() -> Unit)? = null,
    assistant: AssistantViewModel = viewModel(factory = AppViewModelProvider.Factory),
    home: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val voice by assistant.state.collectAsStateWithLifecycle()
    val active by assistant.conversationActive.collectAsStateWithLifecycle()
    val level by assistant.inputLevel.collectAsStateWithLifecycle()
    val settings by assistant.settings.collectAsStateWithLifecycle()
    val ui by home.state.collectAsStateWithLifecycle()
    val aiStatus by assistant.aiStatus.collectAsStateWithLifecycle()
    val versions by home.photoVersions.collectAsStateWithLifecycle()
    val micAction = rememberMicAction(assistant.session)
    val context = androidx.compose.ui.platform.LocalContext.current
    val vehicleId = ui.vehicle?.id
    val photo: Bitmap? = rememberVehiclePhoto(home.photos, vehicleId, versions[vehicleId])
    val kind = DashboardKind.forLevel(settings.explanationLevel)
    // Tick so "stale" readings are noticed; the Everyday dashboard shows no readings, so it doesn't tick.
    val now by androidx.compose.runtime.produceState(java.time.Instant.now(), kind) {
        if (kind != DashboardKind.COMPANION) {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                value = java.time.Instant.now()
            }
        }
    }
    HomeContent(
        contentPadding = contentPadding,
        voice = voice,
        conversationActive = active,
        micLevel = level,
        assistantName = settings.assistantName,
        greeting = remember { greetingFor(LocalTime.now()) },
        ui = ui,
        photo = photo,
        onMic = micAction,
        onPause = { assistant.session.pause() },
        onEnd = assistant.session::end,
        onSuggestion = { q -> assistant.session.submitText(q); onOpenChat() },
        onOpenAppSettings = { openAppSettings(context) },
        onOpenGarage = onOpenGarage,
        onOpenAdapter = onOpenAdapter,
        onOpenSettings = onOpenSettings,
        onOpenResults = onOpenResults,
        onAddVehicle = onAddVehicle,
        onOpenHistory = onOpenHistory,
        onOpenExtras = onOpenExtras,
        onOpenConnectionSettings = onOpenConnectionSettings,
        onOpenChat = onOpenChat,
        items = DashboardPlanner.itemsFor(settings.explanationLevel, settings.dashboard),
        kind = kind,
        onOpenDiagnostics = onOpenDiagnostics,
        onOpenLiveData = onOpenLiveData,
        now = now,
        aiStatus = aiStatus,
        onSelectVehicle = home::select,
        onPhotoPicked = { uri -> vehicleId?.let { home.savePhoto(it, uri) } },
    )
}

/** Stateless home layout (previewable and screenshot-testable). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeContent(
    contentPadding: PaddingValues,
    voice: VoiceState,
    conversationActive: Boolean,
    micLevel: Float,
    assistantName: String,
    greeting: String,
    ui: HomeUiState,
    photo: Bitmap?,
    onMic: () -> Unit,
    onPause: () -> Unit,
    onEnd: () -> Unit,
    onSuggestion: (String) -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenGarage: () -> Unit,
    onOpenAdapter: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenResults: () -> Unit,
    onAddVehicle: () -> Unit,
    onSelectVehicle: (String) -> Unit,
    onPhotoPicked: (android.net.Uri) -> Unit,
    onOpenHistory: () -> Unit = {},
    aiStatus: AiStatus = AiStatus.Off,
    onOpenExtras: () -> Unit = {},
    onOpenConnectionSettings: () -> Unit = {},
    /** Shortcuts to show, from [DashboardPlanner]. Defaults to the Everyday arrangement. */
    items: List<DashboardItem> = DashboardPlanner.defaults(com.aicarcompanion.app.data.local.ExplanationLevel.EVERYDAY),
    kind: DashboardKind = DashboardKind.COMPANION,
    onOpenChat: () -> Unit = {},
    /** Where the Diagnostics shortcut goes; defaults to [onOpenResults]. */
    onOpenDiagnostics: (() -> Unit)? = null,
    /** Where Live readings goes; defaults to the adapter screen if there is no Live Data screen. */
    onOpenLiveData: (() -> Unit)? = null,
    /** Time used to judge whether readings are stale. */
    now: java.time.Instant = java.time.Instant.now(),
) {
    if (kind == DashboardKind.COMPANION) {
        EverydayDriverDashboard(
            contentPadding = contentPadding,
            voice = voice,
            conversationActive = conversationActive,
            micLevel = micLevel,
            assistantName = assistantName,
            greeting = greeting,
            ui = ui,
            aiStatus = aiStatus,
            items = items,
            actions = EverydayActions(
                onMic = onMic, onPause = onPause, onEnd = onEnd, onAsk = onSuggestion, onOpenChat = onOpenChat,
                onOpenResults = onOpenResults, onOpenAdapter = onOpenAdapter, onOpenHistory = onOpenHistory,
                onOpenGarage = onOpenGarage, onOpenSettings = onOpenSettings, onOpenExtras = onOpenExtras,
                onAddVehicle = onAddVehicle, onOpenAppSettings = onOpenAppSettings,
            ),
        )
        return
    }
    val actions = ProDashboardActions(
        onMic = onMic, onPause = onPause, onEnd = onEnd, onAsk = onSuggestion, onOpenChat = onOpenChat,
        onOpenResults = onOpenResults, onOpenDiagnostics = onOpenDiagnostics ?: onOpenResults,
        onOpenLiveData = onOpenLiveData ?: onOpenAdapter, onOpenAdapter = onOpenAdapter, onOpenHistory = onOpenHistory,
        onOpenGarage = onOpenGarage, onOpenSettings = onOpenSettings, onOpenExtras = onOpenExtras,
        onOpenConnectionSettings = onOpenConnectionSettings, onAddVehicle = onAddVehicle, onOpenAppSettings = onOpenAppSettings,
    )
    when (kind) {
        DashboardKind.COMPANION -> Unit
        DashboardKind.WORKSHOP -> DiyDashboard(
            contentPadding, voice, conversationActive, micLevel, assistantName, ui, items, actions, now,
        )
        DashboardKind.TELEMETRY -> AdvancedDashboard(
            contentPadding, voice, conversationActive, assistantName, ui, items, actions, now,
        )
    }
}

/** A few words for the Adapter tile; the full sentence is read by TalkBack. */
internal fun shortConnection(state: com.aicarcompanion.app.diagnostics.obd.ObdConnectionState): String = when (state) {
    is com.aicarcompanion.app.diagnostics.obd.ObdConnectionState.Connected -> if (state.vehicleProtocol != null) "Connected" else "No car"
    is com.aicarcompanion.app.diagnostics.obd.ObdConnectionState.Connecting,
    is com.aicarcompanion.app.diagnostics.obd.ObdConnectionState.Reconnecting -> "Connecting"
    else -> "Not connected"
}

/** Label and color for Penelope's "smarter answers" connection, shown under the orb. */
@Composable
internal fun aiStatusChip(status: AiStatus): Pair<String, Color> {
    val t = Penelope.tokens
    val color = when (status) {
        is AiStatus.Ready, is AiStatus.Standby -> t.brandColors[1]
        is AiStatus.Unavailable -> t.warning
        AiStatus.NotConfigured, AiStatus.Off -> t.textSecondary
    }
    return aiChipText(status) to color
}

/**
 * The chip's words. An unavailable AI names the real reason in a few words: never "Offline"
 * unless the phone is actually offline, and never "daily limit" for a temporary rate limit.
 */
internal fun aiChipText(status: AiStatus): String = when (status) {
    is AiStatus.Ready, is AiStatus.Standby -> "Smarter answers on"
    AiStatus.NotConfigured, AiStatus.Off -> "Basic answers"
    is AiStatus.Unavailable -> when (status.reason) {
        AiFailure.OFFLINE -> "Offline"
        AiFailure.BUSY, AiFailure.TIMEOUT -> "Smarter answers busy"
        AiFailure.QUOTA -> "Daily AI limit reached"
        AiFailure.APP_CHECK -> "App not verified yet"
        else -> "Smarter answers unavailable"
    } + " · using basic answers"
}
