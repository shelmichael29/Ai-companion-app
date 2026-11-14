package com.aicarcompanion.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicarcompanion.app.domain.model.ObservationKind
import com.aicarcompanion.app.domain.model.ServiceKind
import com.aicarcompanion.app.ui.AppViewModelProvider
import com.aicarcompanion.app.ui.adapter.formatReading
import com.aicarcompanion.app.domain.model.SensorReading
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private enum class AddDialog { SERVICE, OBSERVATION, MILEAGE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    viewModel: HistoryViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<AddDialog?>(null) }
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    val timeFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault())
    val miles = NumberFormat.getIntegerInstance()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.vehicle?.let { "History · ${it.title}" } ?: "History") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        if (state.loaded && state.vehicle == null) {
            Text("Add a vehicle to start its history.", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { dialog = AddDialog.SERVICE }, modifier = Modifier.heightIn(min = 48.dp)) { Text("+ Service") }
                    OutlinedButton(onClick = { dialog = AddDialog.OBSERVATION }, modifier = Modifier.heightIn(min = 48.dp)) { Text("+ Note") }
                    OutlinedButton(onClick = { dialog = AddDialog.MILEAGE }, modifier = Modifier.heightIn(min = 48.dp)) { Text("+ Mileage") }
                }
            }

            item { Heading("Service and repairs") }
            if (state.service.isEmpty()) item { Empty("No service records yet.") }
            items(state.service, key = { "s" + it.id }) { r ->
                Entry(
                    title = "${r.title} · ${r.kind.label}",
                    lines = listOfNotNull(
                        listOfNotNull(
                            r.date.format(dateFormat),
                            r.odometerMiles?.let { "${miles.format(it)} mi" },
                            r.performedBy,
                            r.costCents?.let { NumberFormat.getCurrencyInstance(Locale.US).format(it / 100.0) },
                        ).joinToString(" · "),
                        r.notes,
                        r.origin.label.takeIf { r.origin.name == "SAMPLE" },
                    ),
                    onDelete = { viewModel.deleteService(r.id) },
                    deleteLabel = "Delete ${r.title}",
                )
            }

            item { Heading("Notes and observations") }
            item {
                Text(
                    "Verified findings are confirmed by testing or repair. Possible causes stay unconfirmed until verified.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.observations.isEmpty()) item { Empty("No notes yet. Record symptoms like \"rough idle when cold\".") }
            items(state.observations, key = { "o" + it.id }) { o ->
                Entry(
                    title = o.kind.label + (o.relatedCode?.let { " · $it" } ?: ""),
                    lines = listOf(o.text, timeFormat.format(o.createdAt)),
                    emphasize = o.kind.isVerified,
                    onDelete = { viewModel.deleteObservation(o.id) },
                    deleteLabel = "Delete note",
                )
            }

            item { Heading("Trouble code reads") }
            if (state.codeReads.isEmpty()) item { Empty("No codes recorded. Read codes from the Adapter tab.") }
            items(state.codeReads) { read ->
                Entry(
                    title = read.codes.joinToString { "${it.first} (${it.second.label.lowercase()})" },
                    lines = listOf("${timeFormat.format(read.readAt)} · ${read.origin.label}"),
                )
            }

            item { Heading("Saved sensor readings") }
            if (state.readings.isEmpty()) item { Empty("No saved readings. Use \"Save snapshot\" on the Adapter tab.") }
            items(state.readings, key = { "r" + it.id }) { r ->
                Entry(
                    title = "${r.name}: ${formatReading(SensorReading(r.name, r.value, r.unit, r.recordedAt, r.origin, r.pid))}",
                    lines = listOf("${timeFormat.format(r.recordedAt)} · ${r.origin.label}"),
                )
            }

            item { Heading("Mileage log") }
            if (state.mileage.isEmpty()) item { Empty("No mileage entries yet.") }
            items(state.mileage, key = { "m" + it.id }) { m ->
                Entry(title = "${miles.format(m.odometerMiles)} mi", lines = listOf(timeFormat.format(m.recordedAt)))
            }
        }
    }

    when (dialog) {
        AddDialog.SERVICE -> ServiceDialog(viewModel) { dialog = null }
        AddDialog.OBSERVATION -> ObservationDialog(viewModel) { dialog = null }
        AddDialog.MILEAGE -> MileageDialog(viewModel) { dialog = null }
        null -> Unit
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp).semantics { heading() },
    )
}

@Composable
private fun Empty(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Entry(
    title: String,
    lines: List<String>,
    emphasize: Boolean = false,
    onDelete: (() -> Unit)? = null,
    deleteLabel: String = "Delete",
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    color = if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                lines.filter { it.isNotBlank() }.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = deleteLabel) }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun FormDialog(
    title: String,
    error: String?,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Field(label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ServiceDialog(viewModel: HistoryViewModel, onDone: () -> Unit) {
    var form by remember { mutableStateOf(ServiceForm()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    FormDialog("Add service record", error, onDone, onSave = {
        scope.launch { error = viewModel.addService(form); if (error == null) onDone() }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ServiceKind.entries.forEach { k ->
                FilterChip(selected = form.kind == k, onClick = { form = form.copy(kind = k) }, label = { Text(k.label) })
            }
        }
        Field("What was done *", form.title) { form = form.copy(title = it) }
        Field("Date (YYYY-MM-DD) *", form.date) { form = form.copy(date = it) }
        Field("Odometer (miles)", form.odometer, KeyboardType.Number) { form = form.copy(odometer = it) }
        Field("Shop or who did it", form.performedBy) { form = form.copy(performedBy = it) }
        Field("Cost (USD)", form.cost, KeyboardType.Decimal) { form = form.copy(cost = it) }
        Field("Notes (parts, fluids…)", form.notes, singleLine = false) { form = form.copy(notes = it) }
    }
}

@Composable
private fun ObservationDialog(viewModel: HistoryViewModel, onDone: () -> Unit) {
    var form by remember { mutableStateOf(ObservationForm()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    FormDialog("Add note", error, onDone, onSave = {
        scope.launch { error = viewModel.addObservation(form); if (error == null) onDone() }
    }) {
        ObservationKind.entries.forEach { k ->
            FilterChip(
                selected = form.kind == k,
                onClick = { form = form.copy(kind = k) },
                label = { Text(k.label) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
        Field("What did you notice or find? *", form.text, singleLine = false) { form = form.copy(text = it) }
        Field("Related code (optional)", form.relatedCode) { form = form.copy(relatedCode = it.uppercase().take(5)) }
    }
}

@Composable
private fun MileageDialog(viewModel: HistoryViewModel, onDone: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    FormDialog("Record mileage", error, onDone, onSave = {
        scope.launch { error = viewModel.addMileage(text); if (error == null) onDone() }
    }) {
        Field("Odometer (miles)", text, KeyboardType.Number) { text = it.filter(Char::isDigit).take(7) }
    }
}
