package com.aicarcompanion.app.ui.livedata

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.domain.livedata.DashboardLayout
import com.aicarcompanion.app.domain.livedata.GaugeSize
import com.aicarcompanion.app.domain.livedata.GaugeSpec
import com.aicarcompanion.app.domain.livedata.GaugeStyle
import com.aicarcompanion.app.domain.livedata.LiveSensor
import com.aicarcompanion.app.domain.livedata.LiveSensorCatalog
import com.aicarcompanion.app.domain.livedata.SensorValueState
import com.aicarcompanion.app.domain.livedata.UnitChoice
import com.aicarcompanion.app.domain.livedata.UnitConversion
import com.aicarcompanion.app.ui.AppViewModelProvider
import com.aicarcompanion.app.ui.design.PenelopeTopBar
import com.aicarcompanion.app.ui.navigation.LiveDataStatus
import kotlinx.coroutines.delay
import java.time.Instant

/** Everything the workspace can ask for. Tests pass fakes; the screen wires the view model. */
data class WorkspaceActions(
    val onTab: (WorkspaceTab) -> Unit = {},
    val onEditing: (Boolean) -> Unit = {},
    val onSelectLayout: (String) -> Unit = {},
    val onNewLayout: (String) -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onDelete: () -> Unit = {},
    val onAdd: (Int) -> Unit = {},
    val onRemove: (Int) -> Unit = {},
    val onMove: (Int, Int) -> Unit = { _, _ -> },
    val onResize: (Int, GaugeSize) -> Unit = { _, _ -> },
    val onRestyle: (Int, GaugeStyle) -> Unit = { _, _ -> },
    val onUnit: (Int, UnitChoice) -> Unit = { _, _ -> },
    val onDismissNotice: () -> Unit = {},
)

/** The Live Data tab for DIY and Advanced. Reads only what is shown, only while visible. */
@Composable
fun LiveDataWorkspaceScreen(
    level: ExplanationLevel,
    contentPadding: PaddingValues,
    onBack: (() -> Unit)? = null,
    viewModel: LiveDataViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    // Tick once a second so "N s ago" and the stale state stay honest.
    val now by produceState(Instant.now()) {
        while (true) {
            delay(1_000)
            value = Instant.now()
        }
    }
    val actions = remember(viewModel) {
        WorkspaceActions(
            onTab = viewModel::selectTab, onEditing = viewModel::setEditing, onSelectLayout = viewModel::selectLayout,
            onNewLayout = viewModel::newLayout, onRename = viewModel::renameLayout, onDelete = viewModel::deleteLayout,
            onAdd = viewModel::addGauge, onRemove = viewModel::removeGauge, onMove = viewModel::moveGauge,
            onResize = viewModel::resizeGauge, onRestyle = viewModel::restyleGauge, onUnit = viewModel::setGaugeUnit,
            onDismissNotice = viewModel::dismissNotice,
        )
    }
    LiveDataWorkspaceContent(ui, level, now, actions, contentPadding, onBack)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveDataWorkspaceContent(
    ui: LiveDataUiState,
    level: ExplanationLevel,
    now: Instant,
    actions: WorkspaceActions,
    contentPadding: PaddingValues,
    onBack: (() -> Unit)? = null,
) {
    val c = liveColors(level)
    Column(Modifier.fillMaxSize().background(c.background)) {
        PenelopeTopBar("Live data", onBack = onBack)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.testTag("live_workspace"),
        ) {
            full("banner") { StatusBanner(GaugeSemantics.bannerStatus(ui.hasVehicle, ui.obdState, ui.states), now, c) }
            if (ui.notice != null) full("notice") { Notice(ui.notice, c, actions.onDismissNotice) }
            if (ui.hasVehicle && ui.active != null) {
                full("tabs") { Tabs(ui.tab, c, actions.onTab) }
                if (ui.tab == WorkspaceTab.DASHBOARD) {
                    full("layoutBar") { LayoutBar(ui, c, actions) }
                    if (ui.editing) full("editBar") { EditBar(ui.active, c, actions) }
                    dashboardItems(ui, c, now, actions)
                } else {
                    allSensorItems(ui, c, now)
                }
            }
        }
    }
}

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun StatusBanner(status: LiveDataStatus, now: Instant, c: LiveColors) {
    val (title, body) = when (status) {
        LiveDataStatus.NoVehicle -> "No vehicle selected" to "Add or choose a vehicle to see its live data."
        LiveDataStatus.Disconnected -> "Adapter not connected" to "There are no live readings. Connect your OBD-II adapter to see them here."
        LiveDataStatus.WaitingForData -> "Connected, waiting for readings" to "The adapter is connected but the car hasn't sent readings yet. Check the ignition is on."
        LiveDataStatus.Sample -> "Sample data" to "These are demonstration values, not from your car."
        LiveDataStatus.Live -> "Connected and updating" to "Readings come straight from your car."
        is LiveDataStatus.Stale -> "Stale · last updated ${LiveDataStatus.ageLabel(status.lastUpdate, now)}" to "The car stopped sending new readings. These values may be out of date."
    }
    Column(
        Modifier.fillMaxWidth().background(c.panel, RoundedCornerShape(if (c.plain) 6.dp else 16.dp)).padding(14.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title. $body" },
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.primary)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = c.secondary, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Notice(text: String, c: LiveColors, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(c.panel, RoundedCornerShape(8.dp)).padding(start = 14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.warn, modifier = Modifier.weight(1f))
        TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text("OK", color = c.primary) }
    }
}

@Composable
private fun Tabs(selected: WorkspaceTab, c: LiveColors, onTab: (WorkspaceTab) -> Unit) {
    TabRow(
        selectedTabIndex = selected.ordinal, containerColor = c.panel, contentColor = c.primary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        WorkspaceTab.entries.forEach { t ->
            Tab(
                selected = t == selected, onClick = { onTab(t) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("tab_${t.name}"),
                text = { Text(t.label, color = if (t == selected) c.primary else c.secondary, maxLines = 1) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LayoutBar(ui: LiveDataUiState, c: LiveColors, actions: WorkspaceActions) {
    var open by remember { mutableStateOf(false) }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            OutlinedButton(
                onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("layout_picker"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = c.primary),
            ) { Text("Layout: ${ui.active?.name.orEmpty()}", maxLines = 1) }
            DropdownMenu(open, { open = false }) {
                ui.layouts.forEach { l ->
                    DropdownMenuItem(
                        text = { Text(l.name) },
                        onClick = { open = false; actions.onSelectLayout(l.id) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
        Button(
            onClick = { actions.onEditing(!ui.editing) }, modifier = Modifier.heightIn(min = 48.dp).testTag("edit_toggle"),
            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = if (c.plain) c.panel else MaterialTheme.colorScheme.onPrimary),
        ) { Text(if (ui.editing) "Done" else "Edit") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditBar(layout: DashboardLayout, c: LiveColors, actions: WorkspaceActions) {
    var naming by remember { mutableStateOf<NameDialog?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val full = layout.isFull
    Column(Modifier.fillMaxWidth().background(c.panel, RoundedCornerShape(8.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "${layout.gauges.size} of ${DashboardLayout.MAX_GAUGES} gauges",
            style = MaterialTheme.typography.titleSmall, color = if (full) c.warn else c.primary,
            modifier = Modifier.testTag("gauge_count"),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box {
                Button(
                    onClick = { adding = true }, enabled = !full,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("add_gauge"),
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = if (c.plain) c.panel else MaterialTheme.colorScheme.onPrimary),
                ) { Text(if (full) "8 of 8 gauges" else "Add gauge") }
                DropdownMenu(adding, { adding = false }) {
                    LiveSensorCatalog.all.filter { it.pid !in layout.pids }.forEach { s ->
                        DropdownMenuItem(text = { Text(s.label) }, onClick = { adding = false; actions.onAdd(s.pid) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
            OutlinedButton({ naming = NameDialog.NEW }, Modifier.heightIn(min = 48.dp)) { Text("New layout", color = c.primary) }
            OutlinedButton({ naming = NameDialog.RENAME }, Modifier.heightIn(min = 48.dp)) { Text("Rename", color = c.primary) }
            OutlinedButton({ confirmDelete = true }, Modifier.heightIn(min = 48.dp)) { Text("Delete layout", color = c.warn) }
        }
        if (full) Text("A dashboard holds at most ${DashboardLayout.MAX_GAUGES} gauges. Remove one to add another.", style = MaterialTheme.typography.bodyMedium, color = c.secondary)
    }
    naming?.let { mode ->
        var text by remember(mode) { mutableStateOf(if (mode == NameDialog.RENAME) layout.name else "") }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text(if (mode == NameDialog.NEW) "New layout" else "Rename layout") },
            text = { OutlinedTextField(text, { text = it.take(DashboardLayout.MAX_NAME) }, label = { Text("Layout name") }, singleLine = true) },
            confirmButton = {
                TextButton(
                    onClick = { if (mode == NameDialog.NEW) actions.onNewLayout(text) else actions.onRename(text); naming = null },
                    enabled = text.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = { TextButton({ naming = null }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this layout?") },
            text = { Text("Its gauges are removed. Other layouts are not affected.") },
            confirmButton = { TextButton({ confirmDelete = false; actions.onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Keep") } },
        )
    }
}

private enum class NameDialog { NEW, RENAME }

private fun LazyGridScope.dashboardItems(ui: LiveDataUiState, c: LiveColors, now: Instant, actions: WorkspaceActions) {
    val layout = ui.active ?: return
    if (layout.gauges.isEmpty()) {
        full("empty") {
            Text(
                "No gauges yet. Choose Edit, then Add gauge.",
                style = MaterialTheme.typography.bodyLarge, color = c.secondary, modifier = Modifier.padding(8.dp),
            )
        }
        return
    }
    items(
        layout.gauges, key = { it.pid },
        span = { GridItemSpan(it.size.columns) },
    ) { spec ->
        val sensor = LiveSensorCatalog.find(spec.pid) ?: return@items
        val index = layout.gauges.indexOf(spec)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            GaugeCard(
                sensor, spec, ui.states[spec.pid] ?: SensorValueState.TemporarilyUnavailable("Waiting for the first reading"),
                ui.history[spec.pid].orEmpty(), c, now, Modifier.fillMaxWidth(),
            )
            if (ui.editing) GaugeEditControls(sensor, spec, index, layout.gauges.size, c, actions)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GaugeEditControls(sensor: LiveSensor, spec: GaugeSpec, index: Int, count: Int, c: LiveColors, actions: WorkspaceActions) {
    var styleMenu by remember { mutableStateOf(false) }
    var unitMenu by remember { mutableStateOf(false) }
    val units = UnitConversion.choicesFor(sensor.baseUnit)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(
            { actions.onMove(index, index - 1) }, enabled = index > 0,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Move ${sensor.label} earlier" },
        ) { Text("Move up", color = c.primary) }
        TextButton(
            { actions.onMove(index, index + 1) }, enabled = index < count - 1,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Move ${sensor.label} later" },
        ) { Text("Move down", color = c.primary) }
        val nextSize = GaugeSize.entries[(spec.size.ordinal + 1) % GaugeSize.entries.size]
        TextButton(
            { actions.onResize(spec.pid, nextSize) },
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Resize ${sensor.label}, now ${spec.size.label}, next ${nextSize.label}" },
        ) { Text("Size: ${spec.size.label}", color = c.primary) }
        Box {
            TextButton(
                { styleMenu = true },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Style of ${sensor.label}, now ${spec.style.label}" },
            ) { Text("Style: ${spec.style.label}", color = c.primary) }
            DropdownMenu(styleMenu, { styleMenu = false }) {
                GaugeStyle.entries.forEach { st ->
                    DropdownMenuItem(text = { Text(st.label) }, onClick = { styleMenu = false; actions.onRestyle(spec.pid, st) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        if (units.size > 1) Box {
            val current = GaugeSemantics.unitFor(sensor, spec.unit)
            TextButton(
                { unitMenu = true },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Unit of ${sensor.label}, now ${current?.spoken}" },
            ) { Text("Unit: ${current?.symbol}", color = c.primary) }
            DropdownMenu(unitMenu, { unitMenu = false }) {
                units.forEach { u ->
                    DropdownMenuItem(text = { Text(u.symbol) }, onClick = { unitMenu = false; actions.onUnit(spec.pid, u) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        TextButton(
            { actions.onRemove(spec.pid) },
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Remove ${sensor.label} from this dashboard" },
        ) { Text("Remove", color = c.warn) }
    }
}
