package com.aicarcompanion.app.ui.home

import android.graphics.Bitmap
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
import androidx.compose.runtime.produceState
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
    assistant: AssistantViewModel = viewModel(factory = AppViewModelProvider.Factory),
    home: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val voice by assistant.state.collectAsStateWithLifecycle()
    val active by assistant.conversationActive.collectAsStateWithLifecycle()
    val level by assistant.inputLevel.collectAsStateWithLifecycle()
    val settings by assistant.settings.collectAsStateWithLifecycle()
    val ui by home.state.collectAsStateWithLifecycle()
    val versions by home.photoVersions.collectAsStateWithLifecycle()
    val micAction = rememberMicAction(assistant.session)
    val context = androidx.compose.ui.platform.LocalContext.current
    val vehicleId = ui.vehicle?.id
    val photo: Bitmap? by produceState<Bitmap?>(null, vehicleId, versions[vehicleId]) {
        value = vehicleId?.let { home.photos.load(it) }
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
) {
    val t = Penelope.tokens
    val name = assistantName
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
            val orbSize = minOf(maxWidth * 0.72f, 280.dp)
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

        VoiceSessionControls(voice, conversationActive, name, onPause, onEnd, onMic, Modifier.padding(top = 6.dp))

        // Suggestions
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HOME_SUGGESTIONS.forEach { q ->
                SuggestionPill(q, { onSuggestion(q) })
            }
        }

        // Car health summary
        val health = healthSummary(ui.codes, ui.lastRead)
        GlassPanel(
            Modifier.fillMaxWidth().padding(top = 22.dp)
                .clickable(role = Role.Button, onClickLabel = "see car health", onClick = onOpenResults),
            contentPadding = 16.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (health.hasIssues) PenelopeIcons.Alert else PenelopeIcons.Shield, contentDescription = null,
                    tint = if (health.hasIssues) t.warning else t.brandColors[1], modifier = Modifier.size(28.dp),
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(health.title, style = MaterialTheme.typography.titleMedium, color = t.textPrimary)
                    Text(health.subtitle, style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
                }
                Icon(PenelopeIcons.ChevronRight, contentDescription = null, tint = t.textSecondary)
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
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
        )
    }
}
