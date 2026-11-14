package com.aicarcompanion.app.ui.vehicles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicarcompanion.app.domain.model.Aspiration
import com.aicarcompanion.app.domain.model.FuelType
import com.aicarcompanion.app.ui.AppViewModelProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleEditScreen(
    onDone: () -> Unit,
    viewModel: VehicleEditViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val form = viewModel.form
    val errors = viewModel.errors
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isEditing) "Edit vehicle" else "Add vehicle") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
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
            SectionLabel("Vehicle")
            FormField("Year *", form.year, errors[VehicleField.YEAR], KeyboardType.Number) { v ->
                viewModel.update { it.copy(year = v.filter(Char::isDigit).take(4)) }
            }
            FormField("Make *", form.make, errors[VehicleField.MAKE], capitalize = true) { v ->
                viewModel.update { it.copy(make = v) }
            }
            FormField("Model *", form.model, errors[VehicleField.MODEL], capitalize = true) { v ->
                viewModel.update { it.copy(model = v) }
            }
            FormField("Trim", form.trim, errors[VehicleField.TRIM], capitalize = true) { v ->
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
                EnumDropdown("Aspiration", form.aspiration, Aspiration.entries, { it.label }) { v ->
                    viewModel.update { it.copy(aspiration = v) }
                }
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

            Button(onClick = { viewModel.save(onDone) }, modifier = Modifier.fillMaxWidth()) {
                Text("Save")
            }
            if (viewModel.isEditing) {
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete vehicle")
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this vehicle?") },
            text = { Text("This removes the profile from the app.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.delete(onDone) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
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
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
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
