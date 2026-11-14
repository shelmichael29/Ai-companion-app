package com.aicarcompanion.app.ui.vehicles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.ui.AppViewModelProvider

@Composable
fun VehicleListScreen(
    onEditVehicle: (String) -> Unit,
    contentPadding: PaddingValues,
    viewModel: VehicleListViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (state.vehicles.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { Text("No vehicles yet. Tap + to add one.") }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 88.dp, // room for the FAB
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Choose the vehicle shown on the dashboard.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        items(state.vehicles, key = { it.id }) { vehicle ->
            VehicleRow(
                vehicle = vehicle,
                selected = vehicle.id == state.activeVehicleId,
                onSelect = { viewModel.select(vehicle.id) },
                onEdit = { onEditVehicle(vehicle.id) },
            )
        }
    }
}

@Composable
private fun VehicleRow(vehicle: Vehicle, selected: Boolean, onSelect: () -> Unit, onEdit: () -> Unit) {
    Card {
        Row(
            Modifier
                .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(vehicle.title, style = MaterialTheme.typography.titleMedium)
                if (vehicle.nickname != null) Text(vehicle.displayName, style = MaterialTheme.typography.bodyMedium)
                Text(vehicle.engine.description, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onEdit) { Text("Edit") }
        }
    }
}
