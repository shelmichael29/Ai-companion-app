package com.aicarcompanion.enettester

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import kotlin.math.roundToInt
import com.aicarcompanion.enet.PortCheckResult
import com.aicarcompanion.enet.DiscoveryOptions
import com.aicarcompanion.enet.ConnectionOptions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Slider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicarcompanion.enet.ConnectionResult
import com.aicarcompanion.enet.DiscoveryResult
import com.aicarcompanion.enet.StepOutcome
import com.aicarcompanion.enet.TargetPolicy

class MainActivity : ComponentActivity() {
    private val vm: TesterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { TesterScreen(vm, ::copyLog, ::shareLog) }
            }
        }
    }

    private fun copyLog(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ENET tester log", text))
    }

    private fun shareLog(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "BMW ENET tester log").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Export log"))
    }
}

@Composable
private fun TesterScreen(vm: TesterViewModel, onCopy: (String) -> Unit, onShare: (String) -> Unit) {
    val interfaces by vm.monitor.interfaces.collectAsStateWithLifecycle()
    val logEntries by vm.log.entries.collectAsStateWithLifecycle()
    val selected = interfaces.values.firstOrNull { it.interfaceName == vm.selectedName }
    val busy = vm.running != null
    val localOk = selected?.ipv4?.let { TargetPolicy.checkLocal(it) == TargetPolicy.Verdict.Allowed } == true
    val targetProblem = vm.targetProblem()

    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("BMW ENET Connection Tester", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp).semantics { heading() })
            Text(
                "Experimental. Discovery and connection checks only: it never codes, programs, clears codes or activates anything. " +
                    "Nothing is sent until you tap a button.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        item {
            Section("1. USB Ethernet connection") {
                if (interfaces.isEmpty()) {
                    Text("No Ethernet connection. Plug in the USB Ethernet adapter and the ENET cable, and switch the ignition on.")
                }
                interfaces.values.sortedBy { it.interfaceName }.forEach { iface ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(iface.interfaceName == vm.selectedName, role = Role.RadioButton) { vm.selectedName = iface.interfaceName },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(iface.interfaceName == vm.selectedName, onClick = null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(iface.interfaceName, style = MaterialTheme.typography.titleSmall)
                            Text(iface.ipv4?.let { "IPv4 $it" } ?: "No IPv4 address assigned", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                selected?.let { sel ->
                    val problem = sel.ipv4?.let { (TargetPolicy.checkLocal(it) as? TargetPolicy.Verdict.Rejected)?.reason }
                        ?: "This interface has no IPv4 address. The car's gateway doesn't hand out addresses (no DHCP); see the notes below."
                    if (!localOk) Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            Section("2. ENET target") {
                OutlinedTextField(
                    value = vm.targetText,
                    onValueChange = { vm.targetText = it.filter { c -> c.isDigit() || c == '.' }.take(15) },
                    label = { Text("Gateway IPv4 address (optional for discovery)") },
                    placeholder = { Text("e.g. 169.254.x.x") },
                    singleLine = true,
                    isError = targetProblem != null && vm.targetText.isNotBlank(),
                    supportingText = { Text(targetProblem ?: "Must be a private or link-local address on the selected interface.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Check("Also send a DoIP identification request (not expected to answer on a 2011 F10)", vm.includeDoip) { vm.includeDoip = it }
            }
        }

        item {
            Section("3. Gateway discovery") {
                Button(
                    onClick = vm::discover,
                    enabled = !busy && localOk && targetProblem == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Discover gateway") }
                Text(
                    "Sends the HSFZ identification packet (00 00 00 00 00 11) to UDP port 6811 on the selected interface only, " +
                        "then listens for ${"%.0f".format(vm.listenMillis / 1000.0)} seconds (change in Advanced settings).",
                    style = MaterialTheme.typography.bodySmall,
                )
                when (val d = vm.discovery) {
                    null -> Unit
                    is DiscoveryResult.Refused -> Text(d.reason, color = MaterialTheme.colorScheme.error)
                    is DiscoveryResult.Completed -> {
                        d.socket?.let { so ->
                            Text("Socket ${so.endpoint} · broadcast ${if (so.broadcastEnabled == true) "on" else "off"}", style = MaterialTheme.typography.bodySmall)
                        }
                        d.bindingWarnings.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Text(
                            if (d.gateways.isEmpty()) "No gateway answered (${d.probesSent} requests sent)." else "${d.gateways.size} gateway(s) found:",
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        d.gateways.forEach { gw ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("${gw.address} · ${gw.protocol.label}", style = MaterialTheme.typography.titleSmall)
                                    Text("Answered in ${gw.latencyMillis} ms · diag address ${gw.diagAddress ?: "?"}", style = MaterialTheme.typography.bodySmall)
                                    gw.maskedVin?.let { Text("VIN $it", style = MaterialTheme.typography.bodySmall) }
                                    TextButton(onClick = { vm.useDiscovered(gw.address) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Use as target") }
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Section("4. TCP port 6801 check") {
                Text(
                    "Connects to the gateway address entered above on TCP port 6801, measures how long it takes, and closes. " +
                        "Nothing is sent to the car.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = vm::checkPort,
                    enabled = !busy && localOk && vm.targetText.isNotBlank() && targetProblem == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Check TCP port 6801") }
                when (val p = vm.portCheck) {
                    null -> Unit
                    is PortCheckResult.Refused -> Text(p.reason, color = MaterialTheme.colorScheme.error)
                    is PortCheckResult.Open -> Text(
                        "OK · Port 6801 on ${p.target} is open (${p.latencyMillis} ms, from ${p.local.endpoint}).",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    is PortCheckResult.Closed -> Text(
                        "Failed · Port 6801 on ${p.target}: ${p.reason}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }

        item {
            Section("5. Connection test") {
                Check("Send TesterPresent to the gateway (read-only \"are you there?\")", vm.sendTesterPresent) { vm.sendTesterPresent = it }
                Check("Ask the gateway for ignition status (experimental, read-only)", vm.queryIgnition) { vm.queryIgnition = it }
                Check("Check whether DoIP port 13400 is open (connect only)", vm.checkDoipPort) { vm.checkDoipPort = it }
                Button(
                    onClick = vm::testConnection,
                    enabled = !busy && localOk && vm.targetText.isNotBlank() && targetProblem == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Test connection") }
                when (val c = vm.connection) {
                    null -> Unit
                    is ConnectionResult.Refused -> Text(c.reason, color = MaterialTheme.colorScheme.error)
                    is ConnectionResult.Completed -> Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        Text("Results for ${c.target}", style = MaterialTheme.typography.titleSmall)
                        c.steps.forEach { s ->
                            val color = when (s.outcome) {
                                StepOutcome.PASS -> MaterialTheme.colorScheme.primary
                                StepOutcome.FAIL -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text("${s.outcome.label()} · ${s.name}${s.latencyMillis?.let { " · $it ms" } ?: ""}", color = color, style = MaterialTheme.typography.labelLarge)
                            Text(s.detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
                        }
                    }
                }
            }
        }

        vm.running?.let { msg ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp))
                    Text(msg)
                }
            }
        }

        item {
            Section("Advanced settings (Advanced/Professional)") {
                var open by rememberSaveable { mutableStateOf(false) }
                TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (open) "Hide advanced settings" else "Show advanced settings")
                }
                if (open) {
                    SecondsSlider("Discovery listen time", vm.listenMillis, DiscoveryOptions.LISTEN_RANGE, vm::updateListenMillis)
                    SecondsSlider("TCP connect timeout", vm.connectTimeoutMillis, ConnectionOptions.CONNECT_TIMEOUT_RANGE, vm::updateConnectTimeout)
                    TextButton(onClick = vm::restoreAdvancedDefaults, modifier = Modifier.heightIn(min = 48.dp)) { Text("Restore defaults (9 s / 5 s)") }
                }
            }
        }

        item {
            Section("Diagnostic log") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onCopy(vm.log.export()) }, enabled = logEntries.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy") }
                    OutlinedButton(onClick = { onShare(vm.log.export()) }, enabled = logEntries.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Export") }
                    TextButton(onClick = vm.log::clear, enabled = logEntries.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear") }
                }
                Text("VINs and MAC addresses are masked in the log.", style = MaterialTheme.typography.bodySmall)
                if (logEntries.isEmpty()) Text("Nothing logged yet.", style = MaterialTheme.typography.bodySmall)
                logEntries.takeLast(200).forEach { Text(it.message, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
        }

        item {
            Section("Notes for the F10") {
                Text(
                    "• An ENET cable needs pin 8 bridged to pin 16 through a resistor (≈510 Ω) to switch on the car's Ethernet.\n" +
                        "• The car's gateway expects a DHCP server or falls back to a 169.254.x.x address. Android usually needs a DHCP " +
                        "source too: if the interface shows no IPv4 address, a small router between phone and car can provide one.\n" +
                        "• 2011 F10 cars use BMW HSFZ on ports 6801/6811. DoIP (13400) is for newer cars and is expected to be absent.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null)
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun StepOutcome.label() = when (this) {
    StepOutcome.PASS -> "OK"
    StepOutcome.FAIL -> "Failed"
    StepOutcome.NOT_SUPPORTED -> "Not supported"
    StepOutcome.SKIPPED -> "Skipped"
}


/** A whole-seconds slider; TalkBack reads the label and current value. */
@Composable
private fun SecondsSlider(label: String, valueMillis: Long, range: LongRange, onChange: (Long) -> Unit) {
    val seconds = (valueMillis / 1000).toInt()
    val min = (range.first / 1000).toInt()
    val max = (range.last / 1000).toInt()
    Column {
        Text("$label: $seconds s", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = seconds.toFloat(),
            onValueChange = { onChange(it.roundToInt() * 1000L) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = label
                stateDescription = "$seconds seconds"
            },
        )
    }
}
