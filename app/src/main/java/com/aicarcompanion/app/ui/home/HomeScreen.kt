package com.aicarcompanion.app.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.aicarcompanion.app.ai.AiStatus
import com.aicarcompanion.app.dashboard.DashboardItem
import com.aicarcompanion.app.dashboard.DashboardKind
import com.aicarcompanion.app.dashboard.DashboardPlanner
import com.aicarcompanion.app.ui.design.AppBuildInfo
import com.aicarcompanion.app.ui.design.BuildLabel
import com.aicarcompanion.app.ui.design.StatusBadge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.aicarcompanion.app.data.photos.rememberVehiclePhoto
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicarcompanion.app.R
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.ui.AppViewModelProvider
import com.aicarcompanion.app.ui.assistant.AssistantViewModel
import com.aicarcompanion.app.ui.assistant.VoiceSessionControls
import com.aicarcompanion.app.ui.assistant.micActionLabel
import com.aicarcompanion.app.ui.assistant.openAppSettings
import com.aicarcompanion.app.ui.assistant.rememberMicAction
import com.aicarcompanion.app.ui.assistant.statusText
import com.aicarcompanion.app.ui.design.BrandWordmark
import com.aicarcompanion.app.ui.design.GlassIconButton
import com.aicarcompanion.app.ui.design.GlassPanel
import com.aicarcompanion.app.ui.design.LogoMark
import com.aicarcompanion.app.ui.design.OrbMode
import com.aicarcompanion.app.ui.design.PenelopeIcons
import com.aicarcompanion.app.ui.design.SuggestionPill
import com.aicarcompanion.app.ui.design.VoiceOrb
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
        items = DashboardPlanner.itemsFor(settings.explanationLevel, settings.dashboard),
        kind = DashboardKind.forLevel(settings.explanationLevel),
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
) {
    val t = Penelope.tokens
    val name = assistantName
    var showAllSuggestions by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Header
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LogoMark()
            Spacer(Modifier.width(10.dp))
            BrandWordmark(name, Modifier.weight(1f))
            GlassIconButton(Icons.Outlined.Settings, "Settings", onOpenSettings)
        }

        // Welcome
        Text(
            greeting, style = MaterialTheme.typography.headlineMedium, color = t.textPrimary,
            modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
        )
        Text(
            "What can I help you with today?", style = MaterialTheme.typography.bodyLarge, color = t.textSecondary,
            modifier = Modifier.fillMaxWidth(),
        )

        // Orb
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            val orbSize = minOf(maxWidth * 0.72f, 280.dp) * kind.orbScale
            VoiceOrb(
                mode = OrbMode.from(voice),
                level = micLevel,
                onClick = onMic,
                actionLabel = micActionLabel(voice, name),
                size = orbSize,
            )
        }
        AnimatedContent(
            voice.statusText(name),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "status",
        ) { text ->
            Text(
                text, style = MaterialTheme.typography.bodyLarge, color = if (voice is VoiceState.Error) t.danger else t.textSecondary,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            )
        }
        if (voice is VoiceState.Error && (voice as VoiceState.Error).needsPermission) {
            TextButton(onClick = onOpenAppSettings) { Text("Open app settings") }
        }
        val (aiText, aiColor) = aiStatusChip(aiStatus)
        StatusBadge(aiText, aiColor, Modifier.padding(top = 2.dp), icon = PenelopeIcons.Sparkle)

        VoiceSessionControls(voice, conversationActive, name, onPause, onEnd, onMic, Modifier.padding(top = 6.dp))

        // Suggestions
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            (if (showAllSuggestions) HOME_SUGGESTIONS else HOME_SUGGESTIONS.take(2)).forEach { q ->
                SuggestionPill(q, { onSuggestion(q) })
            }
            if (!showAllSuggestions) {
                SuggestionPill("More ideas", { showAllSuggestions = true }, leading = PenelopeIcons.Sparkle)
            }
        }

        // Quick access: soft round tiles instead of boxed cards. Which tiles, and their order,
        // come from the home layout setting for the current knowledge level.
        val health = healthSummary(ui.codes, ui.lastRead)
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 26.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 4,
        ) {
            items.forEach { item ->
                when (item) {
                    DashboardItem.MY_VEHICLE ->
                        QuickTile(PenelopeIcons.Garage, "My Vehicle", ui.vehicle?.let { "${it.year} ${it.make}" } ?: "Add one", onOpenGarage)
                    DashboardItem.CAR_HEALTH -> {
                        val healthShort = when {
                            health.hasIssues -> "${ui.codes.size} code${if (ui.codes.size == 1) "" else "s"}"
                            ui.lastRead == null -> "Not checked"
                            else -> "All clear"
                        }
                        QuickTile(
                            if (health.hasIssues) PenelopeIcons.Alert else PenelopeIcons.Shield, "Car Health",
                            healthShort, onOpenResults, accent = if (health.hasIssues) t.warning else null, spokenDetail = health.title,
                        )
                    }
                    DashboardItem.HISTORY ->
                        QuickTile(PenelopeIcons.History, "History", "Records", onOpenHistory, spokenDetail = "service records and code reads")
                    DashboardItem.SETTINGS ->
                        QuickTile(Icons.Outlined.Settings, "Settings", "Voice", onOpenSettings, spokenDetail = "voice, profile and app settings")
                    DashboardItem.ADAPTER -> {
                        val (connText, _) = connectionSummary(ui.obdState)
                        QuickTile(PenelopeIcons.Plug, "Adapter", shortConnection(ui.obdState), onOpenAdapter, spokenDetail = connText)
                    }
                    DashboardItem.CONNECTION_SETTINGS ->
                        QuickTile(PenelopeIcons.Tune, "Connection", "Protocol", onOpenConnectionSettings, spokenDetail = "protocol, timing and adapter test")
                    DashboardItem.MILEAGE_LOG ->
                        QuickTile(PenelopeIcons.Gauge, "Mileage", "Log", onOpenHistory, spokenDetail = "mileage log")
                    DashboardItem.EXTRAS ->
                        QuickTile(PenelopeIcons.Grid, "Extras", "Tools", onOpenExtras, spokenDetail = "optional tools")
                }
            }
        }

        // Vehicle showcase
        VehicleShowcase(
            vehicle = ui.vehicle,
            otherVehicles = ui.vehicles.filter { it.id != ui.vehicle?.id },
            photo = photo,
            obdState = ui.obdState,
            onSelectVehicle = onSelectVehicle,
            onOpenGarage = onOpenGarage,
            onOpenAdapter = onOpenAdapter,
            onAddVehicle = onAddVehicle,
            onPhotoPicked = onPhotoPicked,
            modifier = Modifier.padding(top = 22.dp, bottom = 12.dp),
        )
        if (AppBuildInfo.showOnHome) BuildLabel(Modifier.fillMaxWidth().padding(bottom = 16.dp))
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
    return when (status) {
        is AiStatus.Ready, is AiStatus.Standby -> "Smarter answers on" to t.brandColors[1]
        is AiStatus.Unavailable -> "Offline · using basic answers" to t.warning
        AiStatus.NotConfigured, AiStatus.Off -> "Basic answers" to t.textSecondary
    }
}

/** A round, glowing shortcut with a label: large touch target, one TalkBack stop. */
@Composable
private fun QuickTile(
    icon: ImageVector,
    label: String,
    detail: String,
    onClick: () -> Unit,
    accent: Color? = null,
    spokenDetail: String = detail,
) {
    val t = Penelope.tokens
    Column(
        Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClickLabel = "open $label", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$label, $spokenDetail" }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(t.brandColors[0].copy(alpha = 0.35f), t.glassFill)))
                .border(1.dp, t.glassBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent ?: t.brandColors[2], modifier = Modifier.size(26.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = t.textPrimary, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        Text(
            detail, style = MaterialTheme.typography.labelSmall, color = t.textSecondary, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}
