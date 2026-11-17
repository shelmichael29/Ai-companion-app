package com.aicarcompanion.app.ui.adapter

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicarcompanion.app.R
import com.aicarcompanion.app.connectivity.AdapterSetupState
import com.aicarcompanion.app.diagnostics.bluetooth.ConnectFailure
import com.aicarcompanion.app.assistant.conversation.SpokenFormat
import com.aicarcompanion.app.diagnostics.bluetooth.BluetoothAvailability
import com.aicarcompanion.app.diagnostics.obd.AdapterDevice
import com.aicarcompanion.app.diagnostics.obd.AdapterRadio
import com.aicarcompanion.app.diagnostics.obd.ConnectionTroubleshooter
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.ui.AppViewModelProvider
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.ui.components.LabeledValue
import com.aicarcompanion.app.ui.components.SectionCard

@Composable
fun AdapterScreen(
    contentPadding: PaddingValues,
    onOpenAdvanced: () -> Unit = {},
    viewModel: AdapterViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.obd.state.collectAsStateWithLifecycle()
    val stats by viewModel.controls.stats.collectAsStateWithLifecycle()
    val info by viewModel.controls.adapterInfo.collectAsStateWithLifecycle()
    val setup by viewModel.setup.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.refresh() }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.refresh() }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val level = viewModel.level
        item { StatusCard(state, setup, viewModel) }
        if (level != ExplanationLevel.EVERYDAY) {
            item { ConnectionDetailsCard(state, viewModel) }
            val tips = ConnectionTroubleshooter.tips(state, stats, info)
            if (tips.isNotEmpty()) item {
                SectionCard("Troubleshooting") { tips.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) } }
            }
        }
        if (level == ExplanationLevel.ADVANCED) {
            item {
                OutlinedButton(onClick = onOpenAdvanced, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Advanced connection settings")
                }
            }
        }

        val connected = state as? ObdConnectionState.Connected
        if (connected?.vehicleProtocol != null) {
            item { LiveDataCard(viewModel) }
        }

        item {
            Text(
                "Read-only: this app only reads standard OBD-II data. It never clears codes, resets adaptations, " +
                    "or activates components. Manufacturer-specific modules (e.g. BMW ABS, airbag, transmission) " +
                    "aren't supported by generic OBD-II.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val permissionNeeded = viewModel.availability == BluetoothAvailability.NO_PERMISSION ||
            (setup as? AdapterSetupState.Unavailable)?.reason == ConnectFailure.PERMISSION_DENIED
        if (permissionNeeded) item {
            SectionCard("Bluetooth permission needed") {
                Text(
                    "Allow \"Nearby devices\" so I can find and connect to your car adapter. " +
                        "I only look for Bluetooth devices while you're connecting, and I don't track your location.",
                )
                Button(
                    onClick = { permissionLauncher.launch(viewModel.requiredPermissions.toTypedArray()) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Allow Bluetooth access") }
            }
        }

        when (viewModel.availability) {
            BluetoothAvailability.NOT_SUPPORTED -> item { Text("This phone doesn't have Bluetooth.") }
            BluetoothAvailability.NO_PERMISSION -> Unit // Permission card above.
            BluetoothAvailability.DISABLED -> item {
                SectionCard("Bluetooth is off") {
                    Button(
                        onClick = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Turn on Bluetooth") }
                }
            }
            BluetoothAvailability.READY -> {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (level == ExplanationLevel.EVERYDAY) "Or choose your adapter" else "Paired devices", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::refresh) { Text("Refresh") }
                    }
                }
                if (viewModel.devices.isEmpty()) {
                    item {
                        Text("No paired devices. Plug the adapter into the OBD-II port (under the dashboard, driver's side), turn the ignition on, then pair it in Android Bluetooth settings. Common PINs are 1234 or 0000.")
                    }
                }
                items(viewModel.devices, key = { it.address }) { device ->
                    DeviceRow(device, state, viewModel.isLikelySameAdapter(device), showTechnical = level != ExplanationLevel.EVERYDAY) {
                        viewModel.connect(device)
                    }
                }
                // Adapters found nearby: from the user's search, or several found by "Connect my adapter".
                val pairedAddresses = viewModel.devices.map { it.address }.toSet()
                val nearby = ((setup as? AdapterSetupState.NeedsChoice)?.candidates.orEmpty() + viewModel.nearby)
                    .distinctBy { it.address }.filter { it.address !in pairedAddresses }
                if (nearby.isNotEmpty()) {
                    item { Text("Found nearby", style = MaterialTheme.typography.titleMedium) }
                    items(nearby, key = { "nearby-" + it.address }) { device ->
                        DeviceRow(device, state, false, showTechnical = level != ExplanationLevel.EVERYDAY) {
                            viewModel.connect(device)
                        }
                    }
                }
                if (level != ExplanationLevel.EVERYDAY) item {
                    val searching = setup is AdapterSetupState.Searching
                    OutlinedButton(
                        onClick = viewModel::searchNearby,
                        enabled = !searching,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(if (searching) "Searching nearby…" else "Search for nearby adapters") }
                }
                item {
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Open Bluetooth settings to pair") }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: ObdConnectionState, setup: AdapterSetupState, viewModel: AdapterViewModel) {
    SectionCard("Connection") {
        val everyday = viewModel.level == ExplanationLevel.EVERYDAY
        val (title, detail) = statusText(state, setup, everyday)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state is ObdConnectionState.Connecting || state is ObdConnectionState.Reconnecting || setup.isBusy()) {
                CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            }
            Icon(painterResource(R.drawable.ic_bluetooth), contentDescription = null)
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Text(detail, style = MaterialTheme.typography.bodyMedium)
        val idle = (state is ObdConnectionState.Disconnected || state is ObdConnectionState.Failed) && !setup.isBusy()
        if (setup.isBusy()) {
            OutlinedButton(onClick = viewModel::disconnect, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
        }
        if (idle && viewModel.bluetoothReady()) {
            if (everyday) {
                val retry = setup is AdapterSetupState.Failed || setup is AdapterSetupState.Unavailable
                Button(onClick = viewModel::connectAutomatically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (retry) "Try again" else "Connect my adapter")
                }
            } else if (viewModel.controls.currentDevice != null) {
                Button(onClick = viewModel::reconnect, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reconnect") }
            }
        }
        if (state is ObdConnectionState.Connected) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.vehicleProtocol == null) {
                    Button(onClick = viewModel::retryVehicle, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check vehicle again") }
                }
                OutlinedButton(onClick = viewModel::disconnect, modifier = Modifier.heightIn(min = 48.dp)) { Text("Disconnect") }
            }
        }
        if (state is ObdConnectionState.Reconnecting) {
            OutlinedButton(onClick = viewModel::disconnect, modifier = Modifier.heightIn(min = 48.dp)) { Text("Stop reconnecting") }
        }
        viewModel.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}

private fun AdapterSetupState.isBusy() =
    this is AdapterSetupState.Searching || this is AdapterSetupState.Found || this is AdapterSetupState.Connecting

/**
 * Title and detail for the status card. While the setup flow is searching, connecting or has
 * failed, its plain-language step is shown. Everyday users never see technical details; DIY and
 * Advanced users also see the underlying error, if any.
 */
fun statusText(state: ObdConnectionState, setup: AdapterSetupState, everyday: Boolean): Pair<String, String> {
    val linkActive = state is ObdConnectionState.Connected || state is ObdConnectionState.Reconnecting
    return when {
        linkActive -> describe(state)
        setup is AdapterSetupState.Searching || setup is AdapterSetupState.Found -> setup.everydayMessage to
            "Make sure the adapter is plugged in under the dashboard and the ignition is on."
        setup is AdapterSetupState.Connecting -> setup.everydayMessage to
            if (everyday || setup.attempt == 1) "This can take a few seconds." else "Attempt ${setup.attempt} of ${setup.maxAttempts}."
        setup is AdapterSetupState.NeedsChoice -> "Choose your adapter" to setup.everydayMessage
        setup is AdapterSetupState.Unavailable -> "Not connected" to setup.everydayMessage
        setup is AdapterSetupState.Failed -> "Not connected" to
            if (everyday || setup.detail.isNullOrBlank()) setup.everydayMessage else "${setup.everydayMessage}\nDetails: ${setup.detail}"
        else -> describe(state)
    }
}

fun describe(state: ObdConnectionState): Pair<String, String> = when (state) {
    ObdConnectionState.Disconnected -> "Not connected" to "Choose your paired adapter below."
    is ObdConnectionState.Connecting -> com.aicarcompanion.app.diagnostics.obd.VehicleLinkStage.of(state).label + "…" to
        "Connecting to ${state.adapterName ?: "adapter"} and checking that your car answers."
    is ObdConnectionState.Connected -> when (com.aicarcompanion.app.diagnostics.obd.VehicleLinkStage.of(state)) {
        com.aicarcompanion.app.diagnostics.obd.VehicleLinkStage.VEHICLE_CONNECTED -> "Vehicle connected" to "${state.adapterName} is connected and your car is responding."
        com.aicarcompanion.app.diagnostics.obd.VehicleLinkStage.DEGRADED, com.aicarcompanion.app.diagnostics.obd.VehicleLinkStage.RECOVERING ->
            "Recovering connection" to "Your car stopped answering for a moment. Penelope is re-checking it automatically."
        else -> "Adapter connected" to
            "${state.adapterName} is connected, but your car isn't responding. Turn the ignition on, then tap \"Check vehicle again\"."
    }
    is ObdConnectionState.Reconnecting -> "Reconnecting (${state.attempt} of ${state.maxAttempts})…" to "Lost connection to ${state.adapterName}."
    is ObdConnectionState.Unavailable -> "Unavailable" to state.reason
    is ObdConnectionState.Failed -> "Not connected" to state.message
}

@Composable
private fun LiveDataCard(viewModel: AdapterViewModel) {
    val readings by viewModel.obd.liveReadings.collectAsStateWithLifecycle(initialValue = emptyList())
    SectionCard("Live readings from your car") {
        if (readings.isEmpty()) Text("Waiting for values…")
        val everyday = viewModel.level == ExplanationLevel.EVERYDAY
        readings.forEach { r ->
            val pid = ObdPid.fromCommand(r.pid)
            LabeledValue(if (everyday && pid != null) pid.friendlyName else r.name, formatReading(r))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::readCodes, enabled = !viewModel.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (viewModel.busy) "Reading…" else "Read trouble codes")
            }
            OutlinedButton(
                onClick = { viewModel.saveSnapshot(readings) },
                enabled = readings.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Save snapshot") }
        }
    }
}

fun formatReading(r: SensorReading): String {
    val pid = ObdPid.fromCommand(r.pid)
    return if (pid != null) SpokenFormat.liveValue(pid, r.value).replace(" degrees Fahrenheit", " °F")
        .replace(" miles per hour", " mph").replace(" percent", "%").replace(" grams per second", " g/s")
    else "%.1f %s".format(java.util.Locale.US, r.value, r.unit)
}

@Composable
private fun DeviceRow(device: AdapterDevice, state: ObdConnectionState, lastUsed: Boolean, showTechnical: Boolean, onConnect: () -> Unit) {
    val busy = state is ObdConnectionState.Connecting || state is ObdConnectionState.Reconnecting
    val isCurrent = (state as? ObdConnectionState.Connected)?.adapterName == device.name
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (showTechnical) Text("${device.radio.label} · ${device.address}", style = MaterialTheme.typography.bodySmall)
                val tags = listOfNotNull(
                    "Looks like an OBD-II adapter".takeIf { device.looksLikeObdAdapter },
                    "Last used".takeIf { lastUsed },
                    "Bluetooth LE adapters not supported yet".takeIf { device.radio == AdapterRadio.LE_ONLY },
                )
                if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Button(
                onClick = onConnect,
                enabled = !busy && !isCurrent,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(if (isCurrent) "Connected" else "Connect") }
        }
        HorizontalDivider()
    }
}

/** DIY and Advanced: what the adapter is, how the link is doing, and manual controls. */
@Composable
private fun ConnectionDetailsCard(state: ObdConnectionState, viewModel: AdapterViewModel) {
    val info by viewModel.controls.adapterInfo.collectAsStateWithLifecycle()
    val stats by viewModel.controls.stats.collectAsStateWithLifecycle()
    SectionCard("Connection details") {
        val connected = state as? ObdConnectionState.Connected
        LabeledValue("Connection", connected?.transport?.label ?: "None")
        LabeledValue("Adapter firmware", info?.firmware ?: "—")
        LabeledValue("Vehicle protocol", info?.protocolName ?: if (connected != null) "Car not answering" else "—")
        info?.voltage?.let { LabeledValue("Voltage at port", "%.1f V".format(it)) }
        if (stats.requests > 0) {
            LabeledValue("Requests / timeouts", "${stats.requests} / ${stats.timeouts}")
            stats.averageLatencyMillis?.let { LabeledValue("Average answer time", "$it ms") }
        }
        if (connected != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::refreshDetails, enabled = !viewModel.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Check voltage")
                }
                OutlinedButton(onClick = viewModel::resetAdapter, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset adapter") }
            }
            Text(
                "Reset restarts the adapter and checks the car again. It only affects the adapter, never your car's computers.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
