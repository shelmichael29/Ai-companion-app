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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicarcompanion.app.data.vehicledb.VinDecodeResult
import com.aicarcompanion.app.domain.model.Aspiration
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.FuelType
import com.aicarcompanion.app.ui.AppViewModelProvider
import com.aicarcompanion.app.ui.components.LabeledValue
import com.aicarcompanion.app.ui.components.SearchableSelector

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VehicleEditScreen(
    onDone: () -> Unit,
    viewModel: VehicleEditViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val detailsRequester = remember { BringIntoViewRequester() }

    LaunchedEffect(viewModel.detailsAppliedCount) {
        if (viewModel.detailsAppliedCount > 0) detailsRequester.bringIntoView()
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
                .verticalScroll(rememberScrollState())
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

            com.aicarcompanion.app.ui.design.GradientButton("Confirm and save", { viewModel.save(onDone) }, Modifier.fillMaxWidth())
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
    val state = viewModel.vinState
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = viewModel.vinInput,
            onValueChange = viewModel::updateVin,
            label = { Text("VIN (17 characters)") },
            supportingText = {
                Text(viewModel.vinLocalError ?: "${viewModel.vinInput.length}/17 · Found on the dashboard by the windshield or the driver's door jamb")
            },
            isError = viewModel.vinLocalError != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(onSearch = { viewModel.decodeVin() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = viewModel::decodeVin,
            enabled = viewModel.vinInput.length == 17 && viewModel.vinLocalError == null && state !is VinState.Decoding,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(if (state is VinState.Decoding) "Decoding…" else "Decode VIN") }

        if (state is VinState.Done) {
            when (val r = state.result) {
                is VinDecodeResult.Decoded -> DecodedVinCard(r, viewModel)
                is VinDecodeResult.NotDecoded -> ErrorText("Couldn't decode this VIN: ${r.reason}")
                is VinDecodeResult.Failed -> ErrorText(r.error.userMessage)
            }
        }
        Text(
            "Source: ${viewModel.vinSourceName}. A VIN decode often can't determine the exact trim, engine " +
                "variant or transmission, so please review the results.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DecodedVinCard(result: VinDecodeResult.Decoded, viewModel: VehicleEditViewModel) {
    val v = result.vehicle
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Decoded by NHTSA", fontWeight = FontWeight.SemiBold)
            LabeledValue("Vehicle", listOfNotNull(v.year?.toString(), v.make, v.model).joinToString(" "))
            v.series?.let { LabeledValue("Series", it) }
            v.trim?.let { LabeledValue("Trim", it) }
            val engine = listOfNotNull(
                v.displacementLiters?.let { "%.1fL".format(java.util.Locale.US, it) },
                v.cylinders?.let { v.layout.shortLabel(it) },
                v.engineCode,
            ).joinToString(" ")
            if (engine.isNotEmpty()) LabeledValue("Engine", engine)
            v.transmission?.let { LabeledValue("Transmission", it) }
            v.drivetrain?.let { LabeledValue("Drivetrain", it) }
            v.bodyClass?.let { LabeledValue("Body", it) }
            if (v.notDetermined.isNotEmpty()) {
                Text(
                    "Not determined from the VIN: ${v.notDetermined.joinToString(", ")}. Please fill these in below if you know them.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            result.warnings.forEach { ErrorText(it) }
            Button(
                onClick = viewModel::useDecodedVin,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 4.dp),
            ) { Text("Use these details") }
        }
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
        FormField("Year *", form.year, errors[VehicleField.YEAR], KeyboardType.Number) { v ->
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
                    KeyboardType.Decimal, Modifier.weight(1f),
                ) { v -> viewModel.update { it.copy(displacementLiters = v) } }
                FormField(
                    "Cylinders", form.cylinders, errors[VehicleField.CYLINDERS],
                    KeyboardType.Number, Modifier.weight(1f),
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
        FormField("Odometer (miles)", form.odometerMiles, errors[VehicleField.ODOMETER], KeyboardType.Number) { v ->
            viewModel.update { it.copy(odometerMiles = v.filter(Char::isDigit).take(7)) }
        }
        FormField("Nickname", form.nickname, errors[VehicleField.NICKNAME], capitalize = true) { v ->
            viewModel.update { it.copy(nickname = v) }
        }
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
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
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier.fillMaxWidth(),
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
        modifier = modifier,
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
