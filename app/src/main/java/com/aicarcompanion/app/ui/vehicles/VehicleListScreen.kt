package com.aicarcompanion.app.ui.vehicles

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.aicarcompanion.app.data.photos.rememberVehiclePhoto
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.ui.AppViewModelProvider
import com.aicarcompanion.app.ui.design.GlassPanel
import com.aicarcompanion.app.ui.design.GradientButton
import com.aicarcompanion.app.ui.design.PenelopeIcons
import com.aicarcompanion.app.ui.design.PenelopeTopBar
import com.aicarcompanion.app.ui.design.StatusBadge
import com.aicarcompanion.app.ui.design.SuggestionPill
import com.aicarcompanion.app.ui.home.CarIllustration
import com.aicarcompanion.app.ui.home.HomeViewModel
import com.aicarcompanion.app.ui.home.connectionSummary
import com.aicarcompanion.app.ui.theme.Penelope

/** The Garage: all of the user's cars, adding cars, and the OBD-II adapter connection. */
@Composable
fun VehicleListScreen(
    onEditVehicle: (String) -> Unit,
    onAddVehicle: () -> Unit,
    onOpenAdapter: () -> Unit,
    contentPadding: PaddingValues,
    /** Null when Garage is a tab; set when it's opened from elsewhere. */
    onBack: (() -> Unit)? = null,
    viewModel: VehicleListViewModel = viewModel(factory = AppViewModelProvider.Factory),
    home: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val t = Penelope.tokens
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val homeUi by home.state.collectAsStateWithLifecycle()
    val versions by home.photoVersions.collectAsStateWithLifecycle()

    Column {
        PenelopeTopBar("Garage", onBack = onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.vehicles.isEmpty()) {
                item {
                    GlassPanel {
                        CarIllustration(Modifier.fillMaxWidth().padding(horizontal = 32.dp))
                        Text("Your garage is empty", style = MaterialTheme.typography.titleLarge, color = t.textPrimary)
                        Text("Add the car you drive so I can explain things about it.", style = MaterialTheme.typography.bodyMedium, color = t.textSecondary)
                    }
                }
            }
            items(state.vehicles, key = { it.id }) { vehicle ->
                val photo = rememberVehiclePhoto(home.photos, vehicle.id, versions[vehicle.id])
                GarageCard(
                    vehicle = vehicle,
                    photo = photo,
                    active = vehicle.id == state.activeVehicleId,
                    onSelect = { viewModel.select(vehicle.id) },
                    onEdit = { onEditVehicle(vehicle.id) },
                )
            }
            item { GradientButton("Add a car", onAddVehicle, Modifier.fillMaxWidth(), leading = PenelopeIcons.Garage) }
            item {
                val (conn, connected) = connectionSummary(homeUi.obdState)
                GlassPanel(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "open adapter connection", onClick = onOpenAdapter)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("OBD-II adapter", style = MaterialTheme.typography.titleMedium, color = t.textPrimary)
                            Text(
                                "A small plug-in adapter lets me read your car's warning codes and live data.",
                                style = MaterialTheme.typography.bodySmall, color = t.textSecondary,
                            )
                            StatusBadge(conn, if (connected) t.success else t.textSecondary, Modifier.padding(top = 8.dp))
                        }
                        Icon(PenelopeIcons.ChevronRight, contentDescription = null, tint = t.textSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun GarageCard(vehicle: Vehicle, photo: Bitmap?, active: Boolean, onSelect: () -> Unit, onEdit: () -> Unit) {
    val t = Penelope.tokens
    GlassPanel(
        Modifier.fillMaxWidth().semantics { stateDescription = if (active) "shown on home" else "not selected" },
        strong = active,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(112.dp).clip(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                if (photo != null) {
                    Image(photo.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(112.dp, 72.dp))
                } else {
                    CarIllustration(Modifier.fillMaxWidth())
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("${vehicle.year} ${vehicle.make}", style = MaterialTheme.typography.labelMedium, color = t.textSecondary)
                Text(listOfNotNull(vehicle.model, vehicle.trim).joinToString(" "), style = MaterialTheme.typography.titleMedium, color = t.textPrimary)
                vehicle.nickname?.let { Text("“$it”", style = MaterialTheme.typography.bodySmall, color = t.textSecondary) }
                Text(vehicle.engine.plainDescription, style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (active) StatusBadge("On home screen", t.brandColors[1], icon = PenelopeIcons.Check)
            else SuggestionPill("Show on Home", onSelect)
            SuggestionPill("Edit", onEdit)
        }
    }
}
