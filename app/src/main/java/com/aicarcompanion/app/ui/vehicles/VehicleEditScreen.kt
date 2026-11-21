package com.aicarcompanion.app.ui.vehicles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicarcompanion.app.domain.model.Aspiration
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.FuelType
import com.aicarcompanion.app.ui.AppViewModelProvider
import com.aicarcompanion.app.ui.components.SearchableSelector

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VehicleEditScreen(
    onDone: () -> Unit,
    onOpenExisting: (String) -> Unit = {},
    viewModel: VehicleEditViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val detailsRequester = remember { BringIntoViewRequester() }
    val scroll = rememberScrollState()
    val inVinFlow = viewModel.vinStage != VinStage.None

    // Scroll to the review form after details are applied, once the guided VIN steps (which replace
    // this form while open) have handed back to it.
    LaunchedEffect(viewModel.detailsAppliedCount, inVinFlow) {
        if (viewModel.detailsAppliedCount > 0 && !inVinFlow) detailsRequester.bringIntoView()
    }

    if (inVinFlow) {
        VinFlowOverlay(viewModel, onOpenExisting = onOpenExisting, onSaved = onDone)
        return
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            com.aicarcompanion.app.ui.design.PenelopeTopBar(if (viewModel.isEditing) "Edit car" else "Add a car", onBack = onDone)
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(scroll)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("Find your vehicle")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                IdentifyMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = viewModel.mode == mode,
                        onClick = { viewModel.selectMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, IdentifyMode.entries.size),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(mode.label) }
                }
            }

            when (viewModel.mode) {
                IdentifyMode.SEARCH -> CatalogSearch(viewModel)
                IdentifyMode.VIN -> VinLookup(viewModel)
                IdentifyMode.MANUAL -> Text(
                    "Enter your vehicle's details below. Use this if lookups can't find it or you're offline.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Box(Modifier.bringIntoViewRequester(detailsRequester)) {
                SectionLabel("Review and confirm details")
            }
            DetailsForm(viewModel)

            // The same recall check and wording as the VIN flow, for cars added by search or manual entry too.
            val context = androidx.compose.ui.platform.LocalContext.current
            val vin = viewModel.form.vin.takeIf { it.isNotBlank() }
            RecallPanel(
                state = viewModel.recallState,
                currentTarget = viewModel.currentRecallTarget,
                make = viewModel.form.make.ifBlank { null },
                hasVin = vin != null,
                onCheck = viewModel::checkRecalls,
                onOpenLink = { openLink(context, it) },
                onCopyVin = vin?.let { v -> { copyVin(context, v) } },
            )

            viewModel.duplicateOf?.let { saved ->
                Text(
                    "You've already added this car as ${saved.displayName}, so I won't save a second copy.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                OutlinedButton(
                    onClick = { onOpenExisting(saved.id) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Open my saved car") }
            }
            com.aicarcompanion.app.ui.design.GradientButton(
                "Confirm and save", { viewModel.save(onDone) }, Modifier.fillMaxWidth(), enabled = !viewModel.saving,
            )
            if (viewModel.isEditing) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Delete vehicle") }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this vehicle?") },
            text = { Text("This removes the profile and its saved history from this phone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.delete(onDone) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CatalogSearch(viewModel: VehicleEditViewModel) {
    val s by viewModel.selection.state.collectAsStateWithLifecycle()
    val c = viewModel.selection
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchableSelector("Year", s.year, s.years, { it.toString() }, c::selectYear, onRetry = c::retry)
        SearchableSelector(
            "Make", s.make, s.makes, { it }, c::selectMake,
            enabled = s.year != null, disabledHint = "Choose a year first", onRetry = c::retry,
        )
        SearchableSelector(
            "Model", s.model, s.models, { it }, c::selectModel,
            enabled = s.make != null, disabledHint = "Choose a make first", onRetry = c::retry,
        )
        SearchableSelector(
            "Engine", s.engine,
            when (val configs = s.configurations) {
                is Options.Loaded -> Options.Loaded(s.engines, configs.fromCache)
                is Options.Error -> configs
                Options.Loading -> Options.Loading
                Options.Idle -> Options.Idle
            },
            { it }, c::selectEngine,
            enabled = s.model != null, disabledHint = "Choose a model first", onRetry = c::retry,
        )
        SearchableSelector(
            "Transmission", s.configuration,
            if (s.engine != null) Options.Loaded(s.transmissions) else Options.Idle,
            { it.transmissionLabel }, c::selectConfiguration,
            enabled = s.engine != null, disabledHint = "Choose an engine first",
        )
        if (s.specs is Options.Loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("  Loading vehicle details…")
            }
        }
        if (s.specs is Options.Error) {
            Text(
                "Couldn't load full details; basic engine information was still filled in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            "Source: ${viewModel.catalogSourceName}. Covers U.S.-market vehicles, and trims are often " +
                "not listed separately. Can't find yours? Try VIN or Manual.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VinLookup(viewModel: VehicleEditViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Scan or type your car's VIN and I'll fill in its details and check for recalls.",
            style = MaterialTheme.typography.bodyMedium,
        )
        com.aicarcompanion.app.ui.design.GradientButton("Scan VIN with the camera", { viewModel.openVinFlow(scan = true) }, Modifier.fillMaxWidth())
        OutlinedButton(
            onClick = { viewModel.openVinFlow(scan = false) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Enter VIN") }
        Text(
            "Source: ${viewModel.vinSourceName}. A VIN decode often can't determine the exact trim, engine " +
                "variant or transmission, so please review the results.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetailsForm(viewModel: VehicleEditViewModel) {
    val form = viewModel.form
    val errors = viewModel.errors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (form.source != com.aicarcompanion.app.domain.model.IdentificationSource.MANUAL) {
            Text(
                "Filled in from: ${form.source.label}. Check each field and correct anything that's wrong.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FormField("Year *", form.year, errors[VehicleField.YEAR], keyboardType = KeyboardType.Number) { v ->
            viewModel.update { it.copy(year = v.filter(Char::isDigit).take(4)) }
        }
        FormField("Make *", form.make, errors[VehicleField.MAKE], capitalize = true) { v ->
            viewModel.update { it.copy(make = v) }
        }
        FormField("Model *", form.model, errors[VehicleField.MODEL], capitalize = true) { v ->
            viewModel.update { it.copy(model = v) }
        }
        FormField("Trim / series (e.g. F10, Sport)", form.trim, errors[VehicleField.TRIM], capitalize = true) { v ->
            viewModel.update { it.copy(trim = v) }
        }

        SectionLabel("Engine")
        EnumDropdown("Fuel type", form.fuelType, FuelType.entries, { it.label }) { v ->
            viewModel.update { it.copy(fuelType = v) }
        }
        if (form.fuelType != FuelType.ELECTRIC) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FormField(
                    "Displacement (L)", form.displacementLiters, errors[VehicleField.DISPLACEMENT],
                    Modifier.weight(1f), KeyboardType.Decimal,
                ) { v -> viewModel.update { it.copy(displacementLiters = v) } }
                FormField(
                    "Cylinders", form.cylinders, errors[VehicleField.CYLINDERS],
                    Modifier.weight(1f), KeyboardType.Number,
                ) { v -> viewModel.update { it.copy(cylinders = v.filter(Char::isDigit).take(2)) } }
            }
            EnumDropdown("Cylinder layout", form.layout, EngineLayout.entries, { it.label }) { v ->
                viewModel.update { it.copy(layout = v) }
            }
            if (form.layout == EngineLayout.UNKNOWN && (form.cylinders.toIntOrNull() ?: 0) >= 5) {
                Text(
                    "Please confirm the layout. Vehicle databases usually don't say whether a six-cylinder " +
                        "is inline or V-shaped (for example, a 2011 BMW 528i's N52 is an inline six).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            EnumDropdown("Aspiration", form.aspiration, Aspiration.entries, { it.label }) { v ->
                viewModel.update { it.copy(aspiration = v) }
            }
            FormField("Engine code (e.g. N52B30)", form.engineCode, null, allCaps = true) { v ->
                viewModel.update { it.copy(engineCode = v) }
            }
            FormField("Fuel grade (e.g. Premium)", form.fuelGrade, null, capitalize = true) { v ->
                viewModel.update { it.copy(fuelGrade = v) }
            }
        }

        SectionLabel("Drivetrain")
        FormField("Transmission", form.transmission, null, capitalize = true) { v ->
            viewModel.update { it.copy(transmission = v) }
        }
        FormField("Drive (e.g. Rear-wheel drive)", form.drivetrain, null, capitalize = true) { v ->
            viewModel.update { it.copy(drivetrain = v) }
        }

        SectionLabel("Optional")
        FormField("VIN", form.vin, errors[VehicleField.VIN], allCaps = true) { v ->
            viewModel.update { it.copy(vin = v.uppercase().take(17)) }
        }
        FormField("Odometer (miles)", form.odometerMiles, errors[VehicleField.ODOMETER], keyboardType = KeyboardType.Number) { v ->
            viewModel.update { it.copy(odometerMiles = v.filter(Char::isDigit).take(7)) }
        }
        FormField("Nickname", form.nickname, errors[VehicleField.NICKNAME], capitalize = true) { v ->
            viewModel.update { it.copy(nickname = v) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun FormField(
    label: String,
    value: String,
    error: String?,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalize: Boolean = false,
    allCaps: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            capitalization = when {
                allCaps -> KeyboardCapitalization.Characters
                capitalize -> KeyboardCapitalization.Words
                else -> KeyboardCapitalization.None
            },
        ),
        // Full width by default; callers can still size it, e.g. with weight in a Row.
        modifier = Modifier.fillMaxWidth().then(modifier),
    )
}

@Composable
private fun <T> EnumDropdown(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("$label: ${optionLabel(selected)}", modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = { onSelect(option); expanded = false },
                )
            }
        }
    }
}
