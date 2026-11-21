package com.aicarcompanion.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.dashboard.DashboardItem
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.MaintenanceRecord
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.ui.assistant.VoiceSessionControls
import com.aicarcompanion.app.ui.assistant.micActionLabel
import com.aicarcompanion.app.ui.design.AppBuildInfo
import com.aicarcompanion.app.ui.design.BrandWordmark
import com.aicarcompanion.app.ui.design.BuildLabel
import com.aicarcompanion.app.ui.design.GlassIconButton
import com.aicarcompanion.app.ui.design.GlassPanel
import com.aicarcompanion.app.ui.design.GradientButton
import com.aicarcompanion.app.ui.design.LogoMark
import com.aicarcompanion.app.ui.design.OrbMode
import com.aicarcompanion.app.ui.design.PenelopeIcons
import com.aicarcompanion.app.ui.design.StatusBadge
import com.aicarcompanion.app.ui.design.SuggestionPill
import com.aicarcompanion.app.ui.design.VoiceOrb
import com.aicarcompanion.app.ui.navigation.LiveDataStatus
import com.aicarcompanion.app.ui.theme.Penelope
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

const val DIY_DASHBOARD_TAG = "dashboard_diy"
const val DIY_ORB_TAG = "diy_orb"

private val SERVICE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
private const val DIY_SUGGESTION_COUNT = 3

/**
 * DIY Enthusiast dashboard: Penelope's look with more working detail than Everyday. A medium
 * orb row for voice, a health card in plain and technical words, diagnostic shortcuts, the last
 * service, two basic sensor tiles and the user's shortcuts. Readings are real or labeled sample.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiyDashboard(
    contentPadding: PaddingValues,
    voice: VoiceState,
    conversationActive: Boolean,
    micLevel: Float,
    assistantName: String,
    ui: HomeUiState,
    items: List<DashboardItem>,
    actions: ProDashboardActions,
    now: Instant = Instant.now(),
) {
    val t = Penelope.tokens
    val status = homeLiveStatus(ui, now)
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(DIY_DASHBOARD_TAG)
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LogoMark()
            Spacer(Modifier.width(10.dp))
            BrandWordmark(assistantName, Modifier.weight(1f))
            GlassIconButton(Icons.Outlined.Settings, "Settings", actions.onOpenSettings)
        }

        VoiceRow(voice, conversationActive, micLevel, assistantName, actions)
        HealthCard(ui, actions)

        // Diagnostic shortcuts.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DiagTile(PenelopeIcons.Engine, "Diagnostics", "Read and explain codes", actions.onOpenDiagnostics)
            DiagTile(PenelopeIcons.Gauge, "Live readings", "Sensors from the car", actions.onOpenLiveData)
            DiagTile(PenelopeIcons.Plug, "Adapter connection", everydayConnection(ui.obdState).first, actions.onOpenAdapter)
        }

        SensorTiles(ui, status)
        MaintenanceCard(ui.lastService, actions)

        val shortcuts = homeShortcuts(items, ui, actions)
        if (shortcuts.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                shortcuts.forEach { s -> ShortcutChip(s, t.glassFill) }
            }
        }
        if (AppBuildInfo.showOnHome) BuildLabel(Modifier.fillMaxWidth().padding(bottom = 16.dp))
        Spacer(Modifier.size(8.dp))
    }
}

// ---- Voice --------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceRow(voice: VoiceState, active: Boolean, level: Float, name: String, actions: ProDashboardActions) {
    val t = Penelope.tokens
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BoxWithConstraints(Modifier.weight(0.42f).testTag(DIY_ORB_TAG), contentAlignment = Alignment.Center) {
                VoiceOrb(OrbMode.from(voice), level, actions.onMic, micActionLabel(voice, name), size = minOf(maxWidth, 160.dp))
            }
            Column(Modifier.weight(0.58f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    everydayStatus(voice, name), style = MaterialTheme.typography.titleMedium,
                    color = if (voice is VoiceState.Error) t.warning else t.textPrimary,
                )
                liveLine(voice)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = t.textSecondary, maxLines = 3)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    VoiceSessionControls(voice, active, name, actions.onPause, actions.onEnd, actions.onMic, compact = true)
                    GlassIconButton(PenelopeIcons.Chat, "Type a question instead", actions.onOpenChat)
                }
                if (voice is VoiceState.Error && voice.needsPermission) {
                    TextButton(onClick = actions.onOpenAppSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("Allow the microphone") }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HOME_SUGGESTIONS.take(DIY_SUGGESTION_COUNT).forEach { q -> SuggestionPill(q, { actions.onAsk(q) }) }
        }
    }
}

// ---- Vehicle health -----------------------------------------------------------------------------

@Composable
private fun HealthCard(ui: HomeUiState, actions: ProDashboardActions) {
    val t = Penelope.tokens
    val health = everydayHealth(ui.vehicle, ui.codes, ui.lastRead, ui.obdState)
    val counts = codeCounts(ui.codes)
    GlassPanel(Modifier.fillMaxWidth().testTag("diy_health"), strong = true, contentPadding = 16.dp) {
        Text(
            "Vehicle health", style = MaterialTheme.typography.titleMedium, color = t.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            health.headline + if (ui.codes.isNotEmpty()) " · check engine light likely on" else "",
            style = MaterialTheme.typography.headlineSmall, color = t.textPrimary, modifier = Modifier.padding(top = 4.dp),
        )
        Text(health.detail, style = MaterialTheme.typography.bodyMedium, color = t.textSecondary, modifier = Modifier.padding(top = 4.dp))
        if (health.sample) StatusBadge("Sample data", t.warning, Modifier.padding(top = 8.dp), icon = PenelopeIcons.Info)
        if (ui.lastRead != null || ui.codes.isNotEmpty()) {
            Text(
                "Stored ${counts.stored} · Pending ${counts.pending} · Permanent ${counts.permanent}",
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace), color = t.textPrimary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        ui.codes.take(3).forEach { c ->
            Row(Modifier.padding(top = 8.dp).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    c.code, style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                    color = t.warning,
                )
                Column(Modifier.weight(1f)) {
                    Text(c.headline ?: c.description, style = MaterialTheme.typography.bodyMedium, color = t.textPrimary)
                    if (c.headline != null) Text(c.description, style = MaterialTheme.typography.labelMedium, color = t.textSecondary)
                }
            }
        }
        Text(
            health.updated ?: "Codes not read yet",
            style = MaterialTheme.typography.labelMedium, color = t.textSecondary, modifier = Modifier.padding(top = 8.dp),
        )
        GradientButton("Read codes", actions.onOpenResults, Modifier.padding(top = 12.dp), leading = PenelopeIcons.Engine)
        actions.clearCodes?.let { slot -> Column(Modifier.padding(top = 8.dp)) { slot() } }
    }
}

// ---- Shortcuts ----------------------------------------------------------------------------------

@Composable
private fun DiagTile(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    val t = Penelope.tokens
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .widthIn(min = 112.dp)
            .heightIn(min = 72.dp)
            .clip(shape)
            .clickable(role = Role.Button, onClickLabel = "open $title", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$title, $detail" },
    ) {
        GlassPanel(Modifier.fillMaxWidth(), shape = shape, contentPadding = 12.dp) {
            Icon(icon, contentDescription = null, tint = t.brandColors[1], modifier = Modifier.size(22.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = t.textPrimary, modifier = Modifier.padding(top = 6.dp))
            Text(detail, style = MaterialTheme.typography.labelSmall, color = t.textSecondary)
        }
    }
}

@Composable
private fun ShortcutChip(s: HomeShortcut, fill: Color) {
    val t = Penelope.tokens
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clip(shape)
            .background(fill)
            .border(1.dp, t.glassBorder, shape)
            .clickable(role = Role.Button, onClickLabel = "open ${s.label}", onClick = s.onClick)
            .semantics(mergeDescendants = true) { contentDescription = s.spoken }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(s.label, style = MaterialTheme.typography.labelLarge, color = t.textPrimary)
    }
}

// ---- Sensors ------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SensorTiles(ui: HomeUiState, status: LiveDataStatus) {
    val shown = if (status.showsValues) ui.readings else emptyList()
    val reason = when (status) {
        LiveDataStatus.NoVehicle -> "Add a vehicle"
        LiveDataStatus.WaitingForData -> "Waiting for the car"
        else -> "Connect your adapter"
    }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SensorTile("Battery voltage", findReading(shown, ObdPid.CONTROL_MODULE_VOLTAGE), reason, status, Modifier.weight(1f))
        SensorTile("Coolant temperature", findReading(shown, ObdPid.COOLANT_TEMP), reason, status, Modifier.weight(1f))
    }
}

@Composable
private fun SensorTile(label: String, reading: SensorReading?, reason: String, status: LiveDataStatus, modifier: Modifier) {
    val t = Penelope.tokens
    val spoken = if (reading == null) "$label, no reading. $reason" else spokenReading(reading) + if (status is LiveDataStatus.Stale) ", stale" else ""
    GlassPanel(
        modifier.widthIn(min = 112.dp).semantics(mergeDescendants = true) { contentDescription = spoken },
        contentPadding = 12.dp,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = t.textSecondary)
        if (reading == null) {
            Text("—", style = MaterialTheme.typography.headlineSmall, color = t.textPrimary)
            Text(reason, style = MaterialTheme.typography.labelSmall, color = t.textSecondary)
        } else {
            Text(
                listOf(formatReadingValue(reading.value), reading.unit).filter { it.isNotBlank() }.joinToString(" "),
                style = MaterialTheme.typography.headlineSmall, color = t.textPrimary,
            )
            when {
                reading.origin == DataOrigin.SAMPLE -> Text("Sample data", style = MaterialTheme.typography.labelSmall, color = t.warning)
                status is LiveDataStatus.Stale -> Text("Stale", style = MaterialTheme.typography.labelSmall, color = t.warning)
            }
        }
    }
}

// ---- Maintenance --------------------------------------------------------------------------------

@Composable
private fun MaintenanceCard(last: MaintenanceRecord?, actions: ProDashboardActions) {
    val t = Penelope.tokens
    GlassPanel(Modifier.fillMaxWidth().testTag("diy_maintenance"), contentPadding = 16.dp) {
        Text(
            "Maintenance", style = MaterialTheme.typography.titleMedium, color = t.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        if (last == null) {
            Text("No service records yet.", style = MaterialTheme.typography.bodyMedium, color = t.textSecondary, modifier = Modifier.padding(top = 4.dp))
        } else {
            Text("Last service: ${last.title}", style = MaterialTheme.typography.bodyLarge, color = t.textPrimary, modifier = Modifier.padding(top = 4.dp))
            val odo = last.odometerMiles?.let { " · %,d mi".format(Locale.US, it) }.orEmpty()
            Text(SERVICE_DATE.format(last.date) + odo, style = MaterialTheme.typography.bodyMedium, color = t.textSecondary)
            if (last.origin == DataOrigin.SAMPLE) StatusBadge("Sample data", t.warning, Modifier.padding(top = 6.dp), icon = PenelopeIcons.Info)
        }
        TextButton(onClick = actions.onOpenHistory, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open History") }
    }
}
