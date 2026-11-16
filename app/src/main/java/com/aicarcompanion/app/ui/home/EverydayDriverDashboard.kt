package com.aicarcompanion.app.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicarcompanion.app.ai.AiStatus
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.dashboard.DashboardItem
import com.aicarcompanion.app.ui.assistant.micActionLabel
import com.aicarcompanion.app.ui.assistant.statusText
import com.aicarcompanion.app.ui.design.AppBuildInfo
import com.aicarcompanion.app.ui.design.BuildLabel
import com.aicarcompanion.app.ui.design.GlassIconButton
import com.aicarcompanion.app.ui.design.OrbMode
import com.aicarcompanion.app.ui.design.PenelopeIcons
import com.aicarcompanion.app.ui.design.StatusBadge
import com.aicarcompanion.app.ui.design.VoiceOrb
import com.aicarcompanion.app.ui.theme.Penelope

/** Everything the Everyday Driver dashboard does when tapped. All of these open real features. */
data class EverydayActions(
    val onMic: () -> Unit,
    val onPause: () -> Unit,
    val onEnd: () -> Unit,
    /** Sends a question to Penelope and opens the conversation. */
    val onAsk: (String) -> Unit,
    val onOpenChat: () -> Unit,
    val onOpenResults: () -> Unit,
    val onOpenAdapter: () -> Unit,
    val onOpenHistory: () -> Unit,
    val onOpenGarage: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenExtras: () -> Unit,
    val onAddVehicle: () -> Unit,
    val onOpenAppSettings: () -> Unit,
)

/**
 * Everyday Driver dashboard: voice-first, beginner-friendly. Penelope's orb is the centerpiece;
 * under it are the voice controls, a plain-language health summary and four quick actions.
 *
 * Layout (top to bottom) follows the design references: vehicle and connection header, the orb,
 * the greeting and question, the voice controls, the bold health status, then quick actions.
 * Screen readers read it in that same order.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EverydayDriverDashboard(
    contentPadding: PaddingValues,
    voice: VoiceState,
    conversationActive: Boolean,
    micLevel: Float,
    assistantName: String,
    greeting: String,
    ui: HomeUiState,
    aiStatus: AiStatus,
    items: List<DashboardItem>,
    actions: EverydayActions,
) {
    val t = Penelope.tokens
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EverydayHeader(ui, actions)

        // Penelope: the centerpiece.
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
            val orbSize = minOf(maxWidth * 0.82f, 310.dp)
            VoiceOrb(OrbMode.from(voice), micLevel, actions.onMic, micActionLabel(voice, assistantName), size = orbSize)
        }

        VoiceInteractionPanel(voice, conversationActive, assistantName, greeting, aiStatus, actions)

        VehicleHealthSummary(
            everydayHealth(ui.vehicle, ui.codes, ui.lastRead, ui.obdState),
            actions,
            Modifier.padding(top = 28.dp),
        )

        QuickActions(actions, Modifier.padding(top = 18.dp))

        // Any extra shortcuts the user added (Customizable layout). Settings, Extras, History and
        // Car Health already have a place on this dashboard, so they aren't repeated.
        val extra = items.filter { it !in COVERED }
        if (extra.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                extra.forEach { item -> ShortcutPill(item, ui, actions) }
            }
        }

        Spacer(Modifier.height(20.dp))
        if (AppBuildInfo.showOnHome) BuildLabel(Modifier.fillMaxWidth().padding(bottom = 16.dp))
    }
}

private val COVERED = setOf(DashboardItem.CAR_HEALTH, DashboardItem.HISTORY, DashboardItem.SETTINGS, DashboardItem.EXTRAS)

// ---- Header ---------------------------------------------------------------------------------------

@Composable
private fun EverydayHeader(ui: HomeUiState, actions: EverydayActions) {
    val t = Penelope.tokens
    val (connText, connected) = everydayConnection(ui.obdState)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassIconButton(PenelopeIcons.Grid, "Extras, optional tools", actions.onOpenExtras)
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .clickable(role = Role.Button, onClickLabel = if (ui.vehicle == null) "add your car" else "open your garage") {
                    if (ui.vehicle == null) actions.onAddVehicle() else actions.onOpenGarage()
                }
                .semantics(mergeDescendants = true) {
                    contentDescription = (ui.vehicle?.title ?: "No car added yet") + ", adapter " + connText.lowercase()
                }
                .heightIn(min = 48.dp)
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                ui.vehicle?.title ?: "Add your car",
                style = MaterialTheme.typography.titleMedium, color = t.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Dot plus words: the status never relies on color alone.
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (connected) t.success else t.textSecondary))
                Spacer(Modifier.width(6.dp))
                Text(connText, style = MaterialTheme.typography.labelMedium, color = t.textSecondary)
            }
        }
        GlassIconButton(Icons.Outlined.Settings, "Settings", actions.onOpenSettings)
    }
}

// ---- Voice --------------------------------------------------------------------------------------

/**
 * Greeting, question and live conversation feedback under the orb, plus the microphone row.
 * Text comes from the real voice session: what Penelope heard, is thinking about, or is saying.
 */
@Composable
fun VoiceInteractionPanel(
    voice: VoiceState,
    conversationActive: Boolean,
    name: String,
    greeting: String,
    aiStatus: AiStatus,
    actions: EverydayActions,
    modifier: Modifier = Modifier,
) {
    val t = Penelope.tokens
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(greeting, style = MaterialTheme.typography.bodyLarge, color = t.textSecondary, textAlign = TextAlign.Center)
        Text(
            "How can I help\nwith your car?",
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium),
            color = t.textPrimary, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp).semantics { heading() },
        )

        // Live feedback: status, then what was heard or said.
        val live = liveLine(voice)
        AnimatedContent(
            everydayStatus(voice, name) to live,
            transitionSpec = { if (t.reducedMotion) fadeIn(snap()) togetherWith fadeOut(snap()) else fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "voiceText",
        ) { (status, line) ->
            Column(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    status, style = MaterialTheme.typography.labelLarge,
                    color = if (voice is VoiceState.Error) t.warning else t.brandColors[1], textAlign = TextAlign.Center,
                )
                if (line != null) {
                    Text(
                        line, style = MaterialTheme.typography.titleMedium, color = t.textPrimary, textAlign = TextAlign.Center,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        if (voice is VoiceState.Error && voice.needsPermission) {
            TextButton(onClick = actions.onOpenAppSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("Allow the microphone") }
        }

        // Microphone row: the big button mirrors the orb; side buttons change with the conversation.
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (conversationActive) {
                GlassIconButton(PenelopeIcons.Pause, "Pause conversation", actions.onPause, size = 56.dp)
            } else {
                GlassIconButton(PenelopeIcons.Chat, "Type a question instead", actions.onOpenChat, size = 56.dp)
            }
            MicButton(voice, name, actions.onMic)
            if (conversationActive) {
                GlassIconButton(PenelopeIcons.Close, "End conversation", actions.onEnd, size = 56.dp)
            } else {
                Spacer(Modifier.size(56.dp))
            }
        }

        val (aiText, aiColor) = aiStatusChip(aiStatus)
        StatusBadge(aiText, aiColor, Modifier.padding(top = 10.dp), icon = PenelopeIcons.Sparkle)
    }
}

/** Short status above the live words. The words themselves are shown once, by [liveLine]. */
internal fun everydayStatus(voice: VoiceState, name: String): String = when (voice) {
    is VoiceState.Listening -> "Listening…"
    is VoiceState.Processing -> "Thinking…"
    is VoiceState.Speaking -> "$name is talking · tap to interrupt"
    else -> voice.statusText(name)
}

/** The words to show under the status: what was heard, or what Penelope is saying. */
internal fun liveLine(voice: VoiceState): String? = when (voice) {
    is VoiceState.Listening -> voice.partialText.takeIf { it.isNotBlank() }?.let { "“$it”" }
    is VoiceState.Processing -> voice.heard.takeIf { it.isNotBlank() }?.let { "“$it”" }
    is VoiceState.Speaking -> voice.reply.text.takeIf { it.isNotBlank() }
    else -> null
}

/** Large glowing microphone button (design reference: round button inside a soft ring). */
@Composable
private fun MicButton(voice: VoiceState, name: String, onClick: () -> Unit) {
    val t = Penelope.tokens
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed && !t.reducedMotion) 0.94f else 1f, label = "micPress")
    val active = voice is VoiceState.Listening || voice is VoiceState.Speaking
    val ring by animateColorAsState(
        if (active) t.brandColors[1].copy(alpha = 0.45f) else t.glassBorder,
        if (t.reducedMotion) snap() else tween(400), label = "micRing",
    )
    Box(
        Modifier
            .size(112.dp)
            .clip(CircleShape)
            .background(t.glassFill)
            .border(1.dp, ring, CircleShape)
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = micActionLabel(voice, name), onClick = onClick)
            .semantics {
                contentDescription = micActionLabel(voice, name)
                stateDescription = OrbMode.from(voice).spoken
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(78.dp)
                .scale(press)
                .clip(CircleShape)
                .background(t.brandGradient)
                .drawBehind {
                    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent), center = Offset(size.width * 0.35f, size.height * 0.3f), radius = size.minDimension * 0.6f))
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(PenelopeIcons.Wave, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
        }
    }
}

// ---- Vehicle health -----------------------------------------------------------------------------

/**
 * Bold, one-glance health status (design reference: "ALL GOOD" / "NO ISSUES"), with an honest
 * explanation and one clear next step. Never shows a result that wasn't read from the car.
 */
@Composable
fun VehicleHealthSummary(health: EverydayHealth, actions: EverydayActions, modifier: Modifier = Modifier) {
    val t = Penelope.tokens
    val (icon, toneColor, toneWord) = when (health.tone) {
        HealthTone.GOOD -> Triple(PenelopeIcons.Check, t.success, "Good")
        HealthTone.ATTENTION -> Triple(PenelopeIcons.Alert, t.warning, "Attention")
        HealthTone.URGENT -> Triple(PenelopeIcons.Alert, t.danger, "Urgent")
        HealthTone.UNKNOWN -> Triple(PenelopeIcons.Info, t.brandColors[1], "Unknown")
    }
    val shape = RoundedCornerShape(32.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(toneColor.copy(alpha = if (t.isDark) 0.16f else 0.10f), t.glassStrong)))
            .border(1.dp, t.glassBorder, shape)
            .padding(horizontal = 22.dp, vertical = 22.dp),
    ) {
        Row(
            Modifier.semantics(mergeDescendants = true) {
                heading()
                contentDescription = "Vehicle health: ${health.headline}" + (if (health.sample) ", sample data" else "")
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = toneColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("VEHICLE HEALTH · ${toneWord.uppercase()}", style = MaterialTheme.typography.labelMedium, color = t.textSecondary)
        }
        Text(
            health.headline.uppercase(),
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
            color = t.textPrimary,
            // Read in normal case, not letter by letter.
            modifier = Modifier.padding(top = 8.dp).semantics { contentDescription = health.headline },
        )
        if (health.sample) StatusBadge("Sample data", t.warning, Modifier.padding(top = 8.dp), icon = PenelopeIcons.Info)
        health.codes.forEach { (code, meaning) ->
            Row(Modifier.padding(top = 12.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
                Text(code, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = toneColor)
                Spacer(Modifier.width(10.dp))
                Text(meaning, style = MaterialTheme.typography.bodyMedium, color = t.textPrimary)
            }
        }
        Text(health.detail, style = MaterialTheme.typography.bodyMedium, color = t.textSecondary, modifier = Modifier.padding(top = 10.dp))
        health.updated?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = t.textSecondary, modifier = Modifier.padding(top = 6.dp))
        }
        PillButton(
            health.actionLabel,
            onClick = when (health.action) {
                HealthAction.SEE_DETAILS, HealthAction.CHECK_CODES -> actions.onOpenResults
                HealthAction.CONNECT_ADAPTER -> actions.onOpenAdapter
                HealthAction.ADD_VEHICLE -> actions.onAddVehicle
            },
            modifier = Modifier.padding(top = 18.dp),
        )
    }
}

/** White pill button (design reference: "Diagnostic" / "Get started"). Dark text on a light pill in both themes. */
@Composable
private fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = Penelope.tokens
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed && !t.reducedMotion) 0.97f else 1f, label = "pillPress")
    val fill = if (t.isDark) Color.White else t.textPrimary
    val ink = if (t.isDark) Color(0xFF0A1220) else Color.White
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .scale(press)
            .clip(RoundedCornerShape(18.dp))
            .background(fill)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = ink, modifier = Modifier.padding(vertical = 14.dp))
    }
}

// ---- Quick actions ------------------------------------------------------------------------------

/** The four beginner quick actions. Each opens a real, working feature. */
@Composable
fun QuickActions(actions: EverydayActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionTile(
                PenelopeIcons.Help, "What's wrong with my car?", "Describe what you notice",
                { actions.onAsk("What's wrong with my car?") }, Modifier.weight(1f),
            )
            ActionTile(PenelopeIcons.Engine, "Check engine light", "What the warning means", actions.onOpenResults, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionTile(PenelopeIcons.Wrench, "Maintenance", "Service records", actions.onOpenHistory, Modifier.weight(1f))
            ActionTile(PenelopeIcons.Chat, "Ask Penelope", "Talk or type", actions.onOpenChat, Modifier.weight(1f))
        }
    }
}

/** Rounded quick-action tile (design reference: icon top-left, label, short detail). */
@Composable
private fun ActionTile(icon: ImageVector, title: String, detail: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = Penelope.tokens
    val shape = RoundedCornerShape(26.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed && !t.reducedMotion) 0.96f else 1f, label = "tilePress")
    val border by animateColorAsState(if (pressed) t.brandColors[1].copy(alpha = 0.5f) else t.glassBorder, label = "tileBorder")
    Column(
        modifier
            .heightIn(min = 128.dp)
            .scale(press)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(t.glassStrong, t.glassFill)))
            .border(1.dp, border, shape)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$title. $detail" }
            .padding(16.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(t.brandColors[0].copy(alpha = if (t.isDark) 0.35f else 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = t.brandColors[if (t.isDark) 2 else 1], modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.weight(1f).heightIn(min = 14.dp))
        Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = t.textPrimary)
        Text(detail, style = MaterialTheme.typography.labelSmall, color = t.textSecondary, modifier = Modifier.padding(top = 2.dp))
    }
}

/** A user-added shortcut (Customizable layout), shown as a small pill. */
@Composable
private fun ShortcutPill(item: DashboardItem, ui: HomeUiState, actions: EverydayActions) {
    val t = Penelope.tokens
    val (icon, label, spoken, onClick) = when (item) {
        DashboardItem.MY_VEHICLE -> Quad(PenelopeIcons.Garage, "My Vehicle", ui.vehicle?.let { "${it.year} ${it.make}" } ?: "Add one", actions.onOpenGarage)
        DashboardItem.ADAPTER -> Quad(PenelopeIcons.Plug, "Adapter", everydayConnection(ui.obdState).first, actions.onOpenAdapter)
        DashboardItem.CONNECTION_SETTINGS -> Quad(PenelopeIcons.Tune, "Connection", "protocol, timing and adapter test", actions.onOpenAdapter)
        DashboardItem.MILEAGE_LOG -> Quad(PenelopeIcons.Gauge, "Mileage", "mileage log", actions.onOpenHistory)
        else -> return
    }
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(t.glassFill)
            .border(1.dp, t.glassBorder, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$label, $spoken" }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = t.brandColors[1], modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = t.textPrimary)
    }
}

private data class Quad(val icon: ImageVector, val label: String, val spoken: String, val onClick: () -> Unit)
