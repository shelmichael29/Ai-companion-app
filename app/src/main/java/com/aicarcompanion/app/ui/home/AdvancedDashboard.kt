package com.aicarcompanion.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.dashboard.DashboardItem
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DiagnosticTroubleCode
import com.aicarcompanion.app.domain.model.DtcStatus
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.ui.assistant.MicIcon
import com.aicarcompanion.app.ui.assistant.micActionLabel
import com.aicarcompanion.app.ui.design.AppBuildInfo
import com.aicarcompanion.app.ui.design.BuildLabel
import com.aicarcompanion.app.ui.design.OrbMode
import com.aicarcompanion.app.ui.design.PenelopeIcons
import com.aicarcompanion.app.ui.navigation.LiveDataStatus
import java.time.Instant

const val ADVANCED_DASHBOARD_TAG = "dashboard_advanced"
const val ADVANCED_MIC_TAG = "advanced_mic"

private val Mono = FontFamily.Monospace
private val PanelShape = RoundedCornerShape(4.dp)
private const val MAX_TILES = 6
private const val MAX_CODES = 5

/**
 * Advanced / Professional dashboard: a dense, plain black-and-grey instrument view. No orb, no
 * suggestion prompts, no decoration. Penelope is one compact microphone button in the header.
 * Order: header, link line, status strip, live tiles, trouble codes, shortcuts.
 */
@Composable
fun AdvancedDashboard(
    contentPadding: PaddingValues,
    voice: VoiceState,
    conversationActive: Boolean,
    assistantName: String,
    ui: HomeUiState,
    items: List<DashboardItem>,
    actions: ProDashboardActions,
    now: Instant = Instant.now(),
) {
    val k = advancedTokens()
    val status = homeLiveStatus(ui, now)
    val shown = if (status.showsValues) ui.readings else emptyList()
    Column(
        Modifier
            .fillMaxSize()
            .background(k.background)
            .testTag(ADVANCED_DASHBOARD_TAG)
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AdvHeader(k, voice, conversationActive, assistantName, ui, actions)
        AdvLinkLine(k, ui)
        AdvStatusStrip(k, ui, shown, status)
        AdvLiveTiles(k, shown, status, actions)
        AdvTroubleCodes(k, ui, actions)
        AdvShortcuts(k, homeShortcuts(items, ui, actions))
        if (AppBuildInfo.showOnHome) BuildLabel(Modifier.fillMaxWidth().padding(bottom = 8.dp))
    }
}

// ---- Header -------------------------------------------------------------------------------------

@Composable
private fun AdvHeader(
    k: AdvancedTokens,
    voice: VoiceState,
    conversationActive: Boolean,
    name: String,
    ui: HomeUiState,
    actions: ProDashboardActions,
) {
    val mode = OrbMode.from(voice)
    val running = conversationActive || voice is VoiceState.Listening || voice is VoiceState.Speaking || voice is VoiceState.Processing
    val paused = voice is VoiceState.Paused
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                name, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = k.primary,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            AdvIconButton(k, PenelopeIcons.Chat, "Open the conversation with $name", actions.onOpenChat)
            if (running && !paused) AdvIconButton(k, PenelopeIcons.Pause, "Pause conversation", actions.onPause)
            if (running || paused) AdvIconButton(k, PenelopeIcons.Close, "End conversation", actions.onEnd)
            val active = voice is VoiceState.Listening || voice is VoiceState.Speaking
            AdvIconButton(
                k, MicIcon, micActionLabel(voice, name),
                onClick = { if (voice !is VoiceState.Processing) actions.onMic() },
                modifier = Modifier.testTag(ADVANCED_MIC_TAG).semantics { stateDescription = mode.spoken },
                outline = if (active) k.ok else k.hairline,
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = if (ui.vehicle == null) "add a vehicle" else "open garage") {
                    if (ui.vehicle == null) actions.onAddVehicle() else actions.onOpenGarage()
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(proVehicleLine(ui.vehicle), style = MaterialTheme.typography.bodyMedium, color = k.secondary)
        }
        Text(
            "Voice · ${mode.spoken}", style = MaterialTheme.typography.labelMedium, color = if (voice is VoiceState.Error) k.warn else k.secondary,
        )
        liveLine(voice)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = k.primary, maxLines = 3) }
        if (voice is VoiceState.Error && voice.needsPermission) {
            AdvTextButton(k, "Allow the microphone", actions.onOpenAppSettings)
        }
    }
}

/** Flat round 48 dp button: hairline outline, no fill gradient. */
@Composable
private fun AdvIconButton(
    k: AdvancedTokens,
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    outline: Color = k.hairline,
) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(k.raised)
            .border(1.dp, outline, CircleShape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = k.primary, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun AdvTextButton(k: AdvancedTokens, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = k.primary)
    }
}

// ---- Link line ----------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvLinkLine(k: AdvancedTokens, ui: HomeUiState) {
    val (_, up) = everydayConnection(ui.obdState)
    val link = proLinkLine(ui.obdState)
    FlowRow(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (up) k.ok else k.tertiary))
            Spacer(Modifier.width(8.dp))
            Text("Link: $link", style = MaterialTheme.typography.labelMedium.copy(fontFamily = Mono), color = k.secondary)
        }
        if (isSampleData(ui)) {
            Text(
                "SAMPLE DATA",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
                color = k.warn,
                modifier = Modifier.border(1.dp, k.warn, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

internal fun isSampleData(ui: HomeUiState): Boolean =
    ui.readings.any { it.origin == DataOrigin.SAMPLE } || ui.codes.any { it.origin == DataOrigin.SAMPLE } ||
        ui.lastRead?.origin == DataOrigin.SAMPLE

// ---- Status strip -------------------------------------------------------------------------------

private class StripCell(val label: String, val value: androidx.compose.ui.text.AnnotatedString, val caption: String, val spoken: String)

@Composable
private fun AdvStatusStrip(k: AdvancedTokens, ui: HomeUiState, shown: List<SensorReading>, status: LiveDataStatus) {
    val read = ui.lastRead != null || ui.codes.isNotEmpty()
    val counts = codeCounts(ui.codes)
    fun plain(text: String, color: Color, family: FontFamily? = null) = buildAnnotatedString {
        withStyle(SpanStyle(color = color, fontFamily = family)) { append(text) }
    }
    val dash = plain("—", k.secondary)

    val engine = when {
        !read -> StripCell("CHECK ENGINE", dash, "Not read yet", "Check engine: not read yet")
        counts.milLikely -> StripCell("CHECK ENGINE", plain("On", k.fault), "Stored code present", "Check engine: on, stored code present")
        else -> StripCell("CHECK ENGINE", plain("Off", k.ok), "No stored codes", "Check engine: off, no stored codes")
    }
    val codes = if (!read) {
        StripCell("CODES", dash, "Stored · Pend · Perm", "Codes: not read yet")
    } else {
        val value = buildAnnotatedString {
            withStyle(SpanStyle(fontFamily = Mono)) {
                withStyle(SpanStyle(color = if (counts.stored > 0) k.fault else k.primary)) { append(counts.stored.toString()) }
                withStyle(SpanStyle(color = k.tertiary)) { append(" · ") }
                withStyle(SpanStyle(color = if (counts.pending > 0) k.warn else k.primary)) { append(counts.pending.toString()) }
                withStyle(SpanStyle(color = k.tertiary)) { append(" · ") }
                withStyle(SpanStyle(color = if (counts.permanent > 0) k.fault else k.primary)) { append(counts.permanent.toString()) }
            }
        }
        StripCell(
            "CODES", value, "Stored · Pend · Perm",
            "Codes: ${counts.stored} stored, ${counts.pending} pending, ${counts.permanent} permanent",
        )
    }
    val readiness = StripCell("READINESS", dash, "Not read yet", "Readiness: not read yet")
    val battery = findReading(shown, ObdPid.CONTROL_MODULE_VOLTAGE)
    val batteryCell = if (battery == null) {
        StripCell("BATTERY", dash, "No live data", "Battery voltage: no live data")
    } else {
        val value = buildAnnotatedString {
            withStyle(SpanStyle(color = k.primary, fontFamily = Mono)) { append(formatReadingValue(battery.value)) }
            withStyle(SpanStyle(color = k.secondary)) { append(" V") }
        }
        val note = when {
            battery.origin == DataOrigin.SAMPLE -> "Sample data"
            status is LiveDataStatus.Stale -> "Stale"
            else -> "Control module"
        }
        StripCell("BATTERY", value, note, "Battery voltage: ${formatReadingValue(battery.value)} volts, $note")
    }
    val cells = listOf(engine, codes, readiness, batteryCell)

    val columns = if (LocalDensity.current.fontScale > 1.15f) 2 else 4
    Column(Modifier.fillMaxWidth()) {
        Divider(k)
        cells.chunked(columns).forEachIndexed { rowIndex, rowCells ->
            if (rowIndex > 0) Divider(k)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                for (i in 0 until columns) {
                    if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(k.hairline))
                    val cell = rowCells.getOrNull(i)
                    Box(Modifier.weight(1f)) { if (cell != null) StripCellView(k, cell) }
                }
            }
        }
        Divider(k)
    }
}

@Composable
private fun StripCellView(k: AdvancedTokens, cell: StripCell) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = cell.spoken },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(cell.label, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.4.sp), color = k.secondary)
        Text(cell.value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp))
        Text(cell.caption, style = MaterialTheme.typography.labelSmall, color = k.secondary)
    }
}

@Composable
private fun Divider(k: AdvancedTokens) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(k.hairline))
}

// ---- Live tiles ---------------------------------------------------------------------------------

@Composable
private fun AdvLiveTiles(k: AdvancedTokens, shown: List<SensorReading>, status: LiveDataStatus, actions: ProDashboardActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(k, "Live data", "Live Data →", actions.onOpenLiveData)
        val tiles = relevantReadings(shown, MAX_TILES)
        if (tiles.isEmpty()) {
            val message = when (status) {
                LiveDataStatus.NoVehicle -> "Add a vehicle to see live data"
                LiveDataStatus.WaitingForData -> "Connected. Waiting for readings from the car"
                else -> "Connect the adapter to see live data"
            }
            Column(
                Modifier.fillMaxWidth().background(k.panel, PanelShape).border(1.dp, k.hairline, PanelShape).padding(12.dp),
            ) {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = k.secondary)
                AdvTextButton(k, "Open Live Data →", actions.onOpenLiveData)
            }
            return@Column
        }
        if (status is LiveDataStatus.Stale) {
            Text("Stale · the car stopped sending new readings", style = MaterialTheme.typography.labelMedium, color = k.warn)
        }
        val columns = if (LocalDensity.current.fontScale > 1.5f) 1 else 2
        tiles.chunked(columns).forEach { rowTiles ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 0 until columns) {
                    val r = rowTiles.getOrNull(i)
                    if (r != null) {
                        LiveTile(k, r, status, actions.onOpenLiveData, Modifier.weight(1f).fillMaxHeight())
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveTile(k: AdvancedTokens, r: SensorReading, status: LiveDataStatus, onClick: () -> Unit, modifier: Modifier) {
    val spoken = spokenReading(r) + if (status is LiveDataStatus.Stale) ", stale" else ""
    Column(
        modifier
            .heightIn(min = 48.dp)
            .background(k.panel, PanelShape)
            .border(1.dp, k.hairline, PanelShape)
            .clickable(role = Role.Button, onClickLabel = "open live data", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(r.name, style = MaterialTheme.typography.labelMedium, color = k.secondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.Bottom) {
            Text(
                formatReadingValue(r.value),
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
                color = k.primary,
            )
            if (r.unit.isNotBlank()) Text(r.unit, style = MaterialTheme.typography.labelMedium, color = k.secondary)
        }
        displayPid(r.pid)?.let {
            Text("PID $it", style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono), color = k.tertiary)
        }
    }
}

// ---- Trouble codes ------------------------------------------------------------------------------

@Composable
private fun AdvTroubleCodes(k: AdvancedTokens, ui: HomeUiState, actions: ProDashboardActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader(k, "Trouble codes", "Diagnostics →", actions.onOpenDiagnostics)
        if (ui.codes.isEmpty()) {
            Text(
                if (ui.lastRead == null) "Codes not read yet. Open Diagnostics to read them." else "No codes stored at the last read.",
                style = MaterialTheme.typography.bodyMedium, color = k.secondary,
            )
            return@Column
        }
        Column(Modifier.fillMaxWidth().background(k.panel, PanelShape).border(1.dp, k.hairline, PanelShape)) {
            ui.codes.take(MAX_CODES).forEachIndexed { i, c ->
                if (i > 0) Divider(k)
                CodeRow(k, c, actions.onOpenResults)
            }
        }
        if (ui.codes.size > MAX_CODES) {
            Text("+${ui.codes.size - MAX_CODES} more in Diagnostics", style = MaterialTheme.typography.labelMedium, color = k.secondary)
        }
    }
}

@Composable
private fun CodeRow(k: AdvancedTokens, c: DiagnosticTroubleCode, onClick: () -> Unit) {
    val title = c.headline ?: c.description
    val color = if (c.status == DtcStatus.PENDING) k.warn else k.fault
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = "open diagnostics", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${c.code}, $title, ${c.status.label}" }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(c.code, style = MaterialTheme.typography.titleSmall.copy(fontFamily = Mono, fontWeight = FontWeight.Bold), color = color)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = k.primary)
            Text(c.status.label, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

// ---- Shortcuts ----------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvShortcuts(k: AdvancedTokens, shortcuts: List<HomeShortcut>) {
    if (shortcuts.isEmpty()) return
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        shortcuts.forEach { s ->
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .widthIn(min = 48.dp)
                    .background(k.raised, PanelShape)
                    .border(1.dp, k.hairline, PanelShape)
                    .clickable(role = Role.Button, onClickLabel = "open ${s.label}", onClick = s.onClick)
                    .semantics(mergeDescendants = true) { contentDescription = s.spoken }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(s.label, style = MaterialTheme.typography.labelLarge, color = k.primary)
            }
        }
    }
}

@Composable
private fun SectionHeader(k: AdvancedTokens, title: String, linkText: String, onLink: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = k.primary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        AdvTextButton(k, linkText, onLink)
    }
}
