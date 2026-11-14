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
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import com.aicarcompanion.app.assistant.conversation.Speaker
import com.aicarcompanion.app.ui.assistant.AssistantViewModel
import com.aicarcompanion.app.ui.assistant.MicButton
import com.aicarcompanion.app.ui.assistant.VoiceStatusText
import com.aicarcompanion.app.ui.assistant.rememberMicAction
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
import com.aicarcompanion.app.ui.adapter.describe
import com.aicarcompanion.app.ui.adapter.formatReading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.aicarcompanion.app.ui.components.LabeledValue
import com.aicarcompanion.app.ui.components.SampleDataBanner
import com.aicarcompanion.app.ui.components.SectionCard
import java.text.NumberFormat
import java.time.ZoneId
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.TroubleCodeRead
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun DashboardScreen(
    onOpenAssistant: () -> Unit,
    onOpenAdapter: () -> Unit,
    onOpenHistory: () -> Unit,
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

        is DashboardUiState.Ready -> DashboardContent(s, onEditVehicle, onOpenAssistant, onOpenAdapter, onOpenHistory, contentPadding)
    }
}

@Composable
private fun DashboardContent(
    state: DashboardUiState.Ready,
    onEditVehicle: (String) -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenAdapter: () -> Unit,
    onOpenHistory: () -> Unit,
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
        item { AssistantCard(onOpenAssistant) }
        if (state.containsSampleData) item { SampleDataBanner() }
        item { VehicleCard(state.vehicle, onEdit = { onEditVehicle(state.vehicle.id) }) }
        item { ConnectionCard(state.obdState, onOpenAdapter) }
        item { TroubleCodesCard(state.troubleCodes, state.lastCodeRead) }
        item { SensorCard(state.sensorReadings) }
        item { MaintenanceCard(state.maintenance, onOpenHistory) }
    }
}

/** One-tap voice access from the dashboard. Uses the same app-wide session as the Assistant tab. */
@Composable
private fun AssistantCard(
    onOpenAssistant: () -> Unit,
    viewModel: AssistantViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val voice by viewModel.state.collectAsStateWithLifecycle()
    val turns by viewModel.turns.collectAsStateWithLifecycle()
    val name by viewModel.assistantName.collectAsStateWithLifecycle()
    val micAction = rememberMicAction(viewModel.session)
    val lastReply = turns.lastOrNull { it.speaker == Speaker.ASSISTANT }
    SectionCard(title = name) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MicButton(voice, name, micAction, size = 72.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                VoiceStatusText(voice, name)
                lastReply?.let { Text(it.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4) }
            }
        }
        TextButton(onClick = onOpenAssistant) { Text("Open conversation") }
    }
}

@Composable
private fun VehicleCard(vehicle: Vehicle, onEdit: () -> Unit) {
    SectionCard(title = vehicle.title) {
        if (vehicle.nickname != null) Text(vehicle.displayName, style = MaterialTheme.typography.bodyLarge)
        LabeledValue("Engine", vehicle.engine.description)
        vehicle.engine.engineCode?.let { LabeledValue("Engine code", it) }
        LabeledValue("Trim", vehicle.trim ?: "—")
        vehicle.transmission?.let { LabeledValue("Transmission", it) }
        vehicle.drivetrain?.let { LabeledValue("Drive", it) }
        LabeledValue("Odometer", vehicle.odometerMiles?.let { "${NumberFormat.getIntegerInstance().format(it)} mi" } ?: "—")
        LabeledValue("VIN", vehicle.vin ?: "Not entered")
        Button(onClick = onEdit, modifier = Modifier.padding(top = 4.dp)) { Text("Edit profile") }
    }
}

@Composable
private fun ConnectionCard(state: ObdConnectionState, onOpenAdapter: () -> Unit) {
    val (status, detail) = describe(state)
    SectionCard(title = "OBD-II adapter") {
        Text(
            status,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(detail, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onOpenAdapter) {
            Text(if (state is ObdConnectionState.Connected) "Live data and codes" else "Connect adapter")
        }
    }
}

@Composable
private fun TroubleCodesCard(codes: List<DiagnosticTroubleCode>, lastRead: TroubleCodeRead?) {
    val isSample = codes.firstOrNull()?.origin == DataOrigin.SAMPLE
    SectionCard(title = "Trouble codes", origin = codes.firstOrNull()?.origin) {
        if (!isSample && lastRead != null) {
            Text(
                "Last read ${DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(lastRead.readAt)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (codes.isEmpty()) {
            Text(if (lastRead == null) "Codes haven't been read yet. Connect your adapter and tap Read trouble codes." else "No trouble codes were reported.")
        }
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
    SectionCard(title = "Live sensor data", origin = readings.firstOrNull()?.origin) {
        if (readings.isEmpty()) Text("No live data. Connect an OBD-II adapter with the ignition on to see sensor values.")
        readings.forEach { r -> LabeledValue(r.name, formatReading(r)) }
    }
}

@Composable
private fun MaintenanceCard(records: List<MaintenanceRecord>, onOpenHistory: () -> Unit) {
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    SectionCard(title = "Service history", origin = records.firstOrNull()?.origin) {
        if (records.isEmpty()) Text("No service records yet.")
        TextButton(onClick = onOpenHistory) { Text("Open history, notes and add records") }
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
