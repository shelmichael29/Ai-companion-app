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
import com.aicarcompanion.app.assistant.conversation.SpokenFormat
import com.aicarcompanion.app.diagnostics.bluetooth.BluetoothAvailability
import com.aicarcompanion.app.diagnostics.obd.AdapterDevice
import com.aicarcompanion.app.diagnostics.obd.AdapterRadio
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.ui.AppViewModelProvider
import com.aicarcompanion.app.ui.components.LabeledValue
import com.aicarcompanion.app.ui.components.SectionCard

@Composable
fun AdapterScreen(
    contentPadding: PaddingValues,
    viewModel: AdapterViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.obd.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refresh() }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.refresh() }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { StatusCard(state, viewModel) }

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

        when (viewModel.availability) {
            BluetoothAvailability.NOT_SUPPORTED -> item { Text("This phone doesn't have Bluetooth.") }
            BluetoothAvailability.NO_PERMISSION -> item {
                SectionCard("Bluetooth permission needed") {
                    Text("Allow \"Nearby devices\" so the app can connect to your paired OBD-II adapter. The app doesn't scan for or track other devices.")
                    Button(
                        onClick = { viewModel.requiredPermission?.let(permissionLauncher::launch) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Allow Bluetooth access") }
                }
            }
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
                        Text("Paired devices", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::refresh) { Text("Refresh") }
                    }
                }
                if (viewModel.devices.isEmpty()) {
                    item {
                        Text("No paired devices. Plug the adapter into the OBD-II port (under the dashboard, driver's side), turn the ignition on, then pair it in Android Bluetooth settings. Common PINs are 1234 or 0000.")
                    }
                }
                items(viewModel.devices, key = { it.address }) { device ->
                    DeviceRow(device, state, viewModel.isLikelySameAdapter(device)) { viewModel.connect(device) }
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
private fun StatusCard(state: ObdConnectionState, viewModel: AdapterViewModel) {
    SectionCard("Connection") {
        val (title, detail) = describe(state)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state is ObdConnectionState.Connecting || state is ObdConnectionState.Reconnecting) {
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
        if (state is ObdConnectionState.Connected) {
            state.adapterVersion?.let { LabeledValue("Adapter", it) }
            state.vehicleProtocol?.let { LabeledValue("Protocol", it) }
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

fun describe(state: ObdConnectionState): Pair<String, String> = when (state) {
    ObdConnectionState.Disconnected -> "Not connected" to "Choose your paired adapter below."
    is ObdConnectionState.Connecting -> "Connecting…" to "Connecting to ${state.adapterName ?: "adapter"} and initializing."
    is ObdConnectionState.Connected -> "Connected to ${state.adapterName}" to
        if (state.vehicleProtocol != null) "Vehicle is responding." else
            "The adapter is connected, but the vehicle isn't responding. Turn the ignition on, then check again."
    is ObdConnectionState.Reconnecting -> "Reconnecting (${state.attempt} of ${state.maxAttempts})…" to "Lost connection to ${state.adapterName}."
    is ObdConnectionState.Unavailable -> "Unavailable" to state.reason
    is ObdConnectionState.Failed -> "Not connected" to state.message
}

@Composable
private fun LiveDataCard(viewModel: AdapterViewModel) {
    val readings by viewModel.obd.liveReadings.collectAsStateWithLifecycle(initialValue = emptyList())
    SectionCard("Live data (from your vehicle)") {
        if (readings.isEmpty()) Text("Waiting for values…")
        readings.forEach { r -> LabeledValue(r.name, formatReading(r)) }
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
private fun DeviceRow(device: AdapterDevice, state: ObdConnectionState, lastUsed: Boolean, onConnect: () -> Unit) {
    val busy = state is ObdConnectionState.Connecting || state is ObdConnectionState.Reconnecting
    val isCurrent = (state as? ObdConnectionState.Connected)?.adapterName == device.name
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text("${device.radio.label} · ${device.address}", style = MaterialTheme.typography.bodySmall)
                val tags = listOfNotNull(
                    "Looks like an OBD-II adapter".takeIf { device.looksLikeObdAdapter },
                    "Last used".takeIf { lastUsed },
                    "BLE adapters not supported yet".takeIf { device.radio == AdapterRadio.LE_ONLY },
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
