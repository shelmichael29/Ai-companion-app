package com.aicarcompanion.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.domain.model.DiagnosticTroubleCode
import com.aicarcompanion.app.domain.model.DtcSeverity
import com.aicarcompanion.app.domain.model.MaintenanceRecord
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.ui.AppViewModelProvider
import com.aicarcompanion.app.ui.components.LabeledValue
import com.aicarcompanion.app.ui.components.SampleDataBanner
import com.aicarcompanion.app.ui.components.SectionCard
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun DashboardScreen(
    onAddVehicle: () -> Unit,
    onEditVehicle: (String) -> Unit,
    contentPadding: PaddingValues,
    viewModel: DashboardViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    when (val s = state) {
        DashboardUiState.Loading -> Box(
            Modifier.fillMaxSize().padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }

        DashboardUiState.NoVehicle -> Column(
            Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No vehicle yet", style = MaterialTheme.typography.headlineSmall)
            Text("Add your vehicle's year, make, model, engine and trim to get started.")
            Button(onClick = onAddVehicle, modifier = Modifier.padding(top = 16.dp)) { Text("Add vehicle") }
        }

        is DashboardUiState.Ready -> DashboardContent(s, onEditVehicle, contentPadding)
    }
}

@Composable
private fun DashboardContent(
    state: DashboardUiState.Ready,
    onEditVehicle: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.containsSampleData) item { SampleDataBanner() }
        item { VehicleCard(state.vehicle, onEdit = { onEditVehicle(state.vehicle.id) }) }
        item { ConnectionCard(state.obdState) }
        item { TroubleCodesCard(state.troubleCodes) }
        item { SensorCard(state.sensorReadings) }
        item { MaintenanceCard(state.maintenance) }
    }
}

@Composable
private fun VehicleCard(vehicle: Vehicle, onEdit: () -> Unit) {
    SectionCard(title = vehicle.title) {
        if (vehicle.nickname != null) Text(vehicle.displayName, style = MaterialTheme.typography.bodyLarge)
        LabeledValue("Engine", vehicle.engine.description)
        LabeledValue("Trim", vehicle.trim ?: "—")
        LabeledValue("Odometer", vehicle.odometerMiles?.let { "${NumberFormat.getIntegerInstance().format(it)} mi" } ?: "—")
        LabeledValue("VIN", vehicle.vin ?: "Not entered")
        Button(onClick = onEdit, modifier = Modifier.padding(top = 4.dp)) { Text("Edit profile") }
    }
}

@Composable
private fun ConnectionCard(state: ObdConnectionState) {
    val (status, detail) = when (state) {
        ObdConnectionState.Disconnected -> "Not connected" to "Plug in an OBD-II adapter to read live data."
        is ObdConnectionState.Connecting -> "Connecting…" to state.transport.label
        is ObdConnectionState.Connected -> "Connected to ${state.adapterName}" to state.transport.label
        is ObdConnectionState.Unavailable -> "Not available yet" to state.reason
        is ObdConnectionState.Failed -> "Connection failed" to state.message
    }
    SectionCard(title = "OBD-II adapter") {
        Text(status, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(detail, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TroubleCodesCard(codes: List<DiagnosticTroubleCode>) {
    SectionCard(title = "Trouble codes", origin = codes.firstOrNull()?.origin) {
        if (codes.isEmpty()) Text("No trouble codes.")
        codes.forEachIndexed { index, dtc ->
            if (index > 0) HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${dtc.code} · ${dtc.status.label}", fontWeight = FontWeight.SemiBold)
                Text(dtc.description, style = MaterialTheme.typography.bodyMedium)
                dtc.explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(
                    dtc.severity.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = severityColor(dtc.severity),
                )
            }
        }
    }
}

@Composable
private fun severityColor(severity: DtcSeverity): Color = when (severity) {
    DtcSeverity.SEVERE -> MaterialTheme.colorScheme.error
    DtcSeverity.MODERATE -> MaterialTheme.colorScheme.tertiary
    DtcSeverity.INFO, DtcSeverity.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun SensorCard(readings: List<SensorReading>) {
    SectionCard(title = "Sensor snapshot", origin = readings.firstOrNull()?.origin) {
        if (readings.isEmpty()) Text("No sensor data.")
        readings.forEach { r ->
            val value = if (r.value % 1.0 == 0.0) r.value.toLong().toString() else "%.1f".format(r.value)
            LabeledValue(r.name, "$value ${r.unit}")
        }
    }
}

@Composable
private fun MaintenanceCard(records: List<MaintenanceRecord>) {
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    SectionCard(title = "Service history", origin = records.firstOrNull()?.origin) {
        if (records.isEmpty()) Text("No service records yet.")
        records.forEachIndexed { index, record ->
            if (index > 0) HorizontalDivider()
            Column {
                Text(record.title, fontWeight = FontWeight.SemiBold)
                val meta = listOfNotNull(
                    record.date.format(dateFormat),
                    record.odometerMiles?.let { "${NumberFormat.getIntegerInstance().format(it)} mi" },
                    record.performedBy,
                ).joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.bodySmall)
                record.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
