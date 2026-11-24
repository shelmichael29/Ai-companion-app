package com.aicarcompanion.app.ui.adapter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicarcompanion.app.diagnostics.obd.AdaptiveTiming
import com.aicarcompanion.app.diagnostics.obd.DiagnosticStep
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionConfig
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdProtocol
import com.aicarcompanion.app.diagnostics.obd.ObdTransport
import com.aicarcompanion.app.diagnostics.obd.TransportSupport
import com.aicarcompanion.app.ui.AppViewModelProvider
import com.aicarcompanion.app.ui.components.LabeledValue
import com.aicarcompanion.app.ui.components.SectionCard
import kotlin.math.roundToInt

/**
 * Advanced / professional connection settings. Only options the implemented transport
 * (Bluetooth Classic ELM327) supports are shown; options a connected adapter rejected are
 * marked as such. Nothing here can send a command outside the read-only allowlist.
 */
@Composable
fun AdvancedConnectionScreen(
    contentPadding: PaddingValues,
    viewModel: AdvancedConnectionViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val state by viewModel.obd.state.collectAsStateWithLifecycle()
    val info by viewModel.controls.adapterInfo.collectAsStateWithLifecycle()
    val stats by viewModel.controls.stats.collectAsStateWithLifecycle()
    val log by viewModel.controls.trafficLog.entries.collectAsStateWithLifecycle()
    val caps = info?.capabilities
    var confirmRestore by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                if (viewModel.appliesNow) {
                    "These settings are active. Changes to protocol, timing or response format reconnect the adapter automatically."
                } else {
                    "Saved, but not active: Everyday and DIY levels always use the recommended automatic settings. Switch to Advanced in Settings to use them."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (viewModel.appliesNow) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
            )
        }

        item {
            SectionCard("Adapter connection type") {
                ObdTransport.entries.forEach { t ->
                    val available = TransportSupport.isImplemented(t)
                    if (available) {
                        LabeledValue(t.label, "ELM327, in use")
                    }
                }
                Text(
                    "Bluetooth Low Energy, Wi-Fi and USB adapters aren't supported in this version, so they aren't offered. " +
                        "The connection layer is built so they can be added without changing diagnostics.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            SectionCard("OBD-II protocol") {
                ObdProtocol.entries.forEach { p ->
                    ChoiceRow(p.label, config.protocol == p) { viewModel.update { it.copy(protocol = p) } }
                }
                if (config.protocol != ObdProtocol.AUTO) {
                    SwitchRow(
                        "Fall back to automatic if the car doesn't answer",
                        "Recommended. Without it, a wrong protocol means no data at all.",
                        config.protocolFallbackToAuto,
                    ) { v -> viewModel.update { it.copy(protocolFallbackToAuto = v) } }
                    if (caps?.manualProtocol == false) {
                        Warning("The connected adapter rejected this protocol, so it's using automatic detection.")
                    }
                }
                info?.protocolName?.let { LabeledValue("Car is answering on", it) }
            }
        }

        item {
            SectionCard("Timing") {
                SliderRow(
                    "Response timeout", config.responseTimeoutMillis, ObdConnectionConfig.TIMEOUT_RANGE, step = 500,
                    format = { "%.1f s".format(it / 1000.0) },
                ) { v -> viewModel.update { it.copy(responseTimeoutMillis = v) } }
                SliderRow("Retries after a timeout", config.retries, ObdConnectionConfig.RETRY_RANGE, step = 1, format = { "$it" }) { v ->
                    viewModel.update { it.copy(retries = v) }
                }
                Text("Adapter timing", style = MaterialTheme.typography.titleSmall)
                AdaptiveTiming.entries.forEach { t ->
                    val rejected = when (t) {
                        AdaptiveTiming.NORMAL -> caps?.adaptiveTiming == false
                        AdaptiveTiming.AGGRESSIVE -> caps?.aggressiveTiming == false
                        AdaptiveTiming.OFF -> caps?.fixedTimeout == false
                    } && config.adaptiveTiming == t
                    ChoiceRow(t.label + if (rejected) " (not supported by this adapter)" else "", config.adaptiveTiming == t) {
                        viewModel.update { it.copy(adaptiveTiming = t) }
                    }
                }
                if (config.adaptiveTiming == AdaptiveTiming.OFF) {
                    SliderRow(
                        "Adapter wait for the car", config.adapterTimeoutMillis, ObdConnectionConfig.ADAPTER_TIMEOUT_RANGE, step = 50,
                        format = { "$it ms" },
                    ) { v -> viewModel.update { it.copy(adapterTimeoutMillis = v) } }
                }
            }
        }

        item {
            SectionCard("Live data") {
                SliderRow(
                    "Pause between updates", config.pollIntervalMillis, ObdConnectionConfig.POLL_RANGE, step = 250,
                    format = { "%.2f s".format(it / 1000.0) },
                ) { v -> viewModel.update { it.copy(pollIntervalMillis = v) } }
                Text(
                    "Shorter pauses update faster but load slow adapters; a reading that doesn't arrive is shown as missing, never guessed.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            SectionCard("Reconnection") {
                SwitchRow("Reconnect automatically", "Tries again when the Bluetooth link drops.", config.autoReconnect) { v ->
                    viewModel.update { it.copy(autoReconnect = v) }
                }
                if (config.autoReconnect) {
                    SliderRow("Attempts", config.reconnectAttempts, 1..ObdConnectionConfig.RECONNECT_RANGE.last, step = 1, format = { "$it" }) { v ->
                        viewModel.update { it.copy(reconnectAttempts = v) }
                    }
                }
            }
        }

        item {
            SectionCard("Adapter identification") {
                if (info == null) {
                    Text("Connect to an adapter to see its details.")
                } else {
                    LabeledValue("Firmware", info?.firmware ?: "Not reported")
                    LabeledValue("Description", info?.description ?: "Not reported")
                    LabeledValue("Voltage at port", info?.voltage?.let { "%.1f V".format(it) } ?: "Not measured")
                    LabeledValue("Protocol", info?.protocolName ?: "Car not answering")
                    if (info?.likelyClone == true) {
                        Warning("Firmware \"v2.1\" isn't a genuine ELM327 release; this is likely a low-cost clone. Some settings may be ignored.")
                    }
                    OutlinedButton(onClick = viewModel::refreshDetails, modifier = Modifier.heightIn(min = 48.dp)) { Text("Read details again") }
                }
            }
        }

        item {
            SectionCard("Connection test") {
                LabeledValue("Requests this session", "${stats.requests}")
                LabeledValue("Timeouts", "${stats.timeouts}")
                LabeledValue("Retries", "${stats.retries}")
                LabeledValue("Reconnects", "${stats.reconnects}")
                LabeledValue("Average / slowest answer", stats.averageLatencyMillis?.let { "$it ms / ${stats.maxLatencyMillis} ms" } ?: "—")
                Button(
                    onClick = viewModel::runTest,
                    enabled = !viewModel.testing && state is ObdConnectionState.Connected,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(if (viewModel.testing) "Testing…" else "Run connection test") }
                viewModel.report?.let { report ->
                    report.steps.forEach { step ->
                        val color = when (step.result) {
                            DiagnosticStep.Result.PASS -> MaterialTheme.colorScheme.primary
                            DiagnosticStep.Result.WARN -> MaterialTheme.colorScheme.tertiary
                            DiagnosticStep.Result.FAIL -> MaterialTheme.colorScheme.error
                            DiagnosticStep.Result.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Column {
                            Text("${step.result.label} · ${step.title}", style = MaterialTheme.typography.labelLarge, color = color)
                            Text(step.detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        item {
            SectionCard("Communication log") {
                SwitchRow(
                    "Record adapter traffic",
                    "Kept in memory only and erased when turned off. VINs and other vehicle identifiers are redacted; adapter addresses are never recorded.",
                    config.communicationLog,
                ) { v -> viewModel.update { it.copy(communicationLog = v) } }
                if (config.communicationLog) {
                    if (log.isEmpty()) Text("No traffic recorded yet.", style = MaterialTheme.typography.bodySmall)
                    log.takeLast(40).forEach { e ->
                        Text(
                            "${e.command} → ${e.response.replace('\r', ' ')}  (${e.elapsedMillis} ms, ${e.outcome.name.lowercase()})",
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Traffic log", viewModel.controls.trafficLog.export())) },
                            enabled = log.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("Copy log") }
                        TextButton(onClick = viewModel.controls.trafficLog::clear, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear") }
                    }
                }
            }
        }

        item {
            SectionCard("Expert ELM327 options") {
                Text(
                    "Every command still passes the read-only safety check: nothing here can clear codes, reset modules, " +
                        "run actuator tests or write to the car.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    Modifier.fillMaxWidth().toggleable(viewModel.expertUnlocked, role = Role.Checkbox) { viewModel.expertUnlocked = it }
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(viewModel.expertUnlocked, onCheckedChange = null)
                    Text("I understand these options are for troubleshooting", Modifier.padding(start = 8.dp))
                }
                if (viewModel.expertUnlocked) {
                    SwitchRow(
                        "Compact responses (AT S0)", "Removes spaces from adapter replies for slightly faster transfers.",
                        config.compactResponses,
                    ) { v -> viewModel.update { it.copy(compactResponses = v) } }
                    if (config.compactResponses && caps?.compactResponses == false) Warning("This adapter ignored AT S0; normal responses are used.")
                    ExpertConsole(viewModel)
                }
            }
        }

        item {
            OutlinedButton(
                onClick = { confirmRestore = true },
                enabled = !config.isRecommended,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(if (config.isRecommended) "Using recommended defaults" else "Restore recommended defaults") }
        }
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore recommended defaults?") },
            text = { Text("Automatic protocol, adaptive timing, 3-second timeout, 1-second updates and automatic reconnection. The communication log is turned off and erased.") },
            confirmButton = { TextButton(onClick = { viewModel.restoreDefaults(); confirmRestore = false }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ExpertConsole(viewModel: AdvancedConnectionViewModel) {
    var command by rememberSaveable { mutableStateOf("") }
    Text("Send a read-only command", style = MaterialTheme.typography.titleSmall)
    Text(
        "Allowed: AT I, AT @1, AT RV, AT DP/DPN and OBD-II modes 01, 02, 03, 06, 07, 09, 0A. Settings like echo, headers or " +
            "protocol are changed above instead, so the app's own reading stays reliable.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = command, onValueChange = { command = it.take(12) }, singleLine = true,
            placeholder = { Text("e.g. 010C") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { viewModel.send(command); command = "" }),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { viewModel.send(command); command = "" }, modifier = Modifier.padding(start = 8.dp).heightIn(min = 48.dp)) { Text("Send") }
    }
    viewModel.consoleLines.forEach { (cmd, reply) ->
        Text("> $cmd\n$reply", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SwitchRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onCheckedChange = null)
    }
}

/** Slider that saves only when the user lets go, so dragging doesn't reconnect the adapter repeatedly. */
@Composable
private fun SliderRow(label: String, value: Int, range: IntRange, step: Int, format: (Int) -> String, onCommit: (Int) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val snapped = ((local / step).roundToInt() * step).coerceIn(range)
    Column {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(format(snapped), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = local, onValueChange = { local = it },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
            onValueChangeFinished = { onCommit(snapped) },
        )
    }
}

@Composable
private fun Warning(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}
