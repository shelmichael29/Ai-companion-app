package com.aicarcompanion.app.ui.diy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.aicarcompanion.app.ui.design.PenelopeIcons
import com.aicarcompanion.app.ui.home.HomeUiState
import com.aicarcompanion.app.ui.home.proVehicleLine
import java.util.Locale

const val DIY_HEADER_TAG = "diy_header"

/**
 * The selected vehicle, tappable to open the garage (or to add a vehicle when there is none), a
 * vehicle switcher when there is more than one, and app settings. The VIN is never shown.
 */
@Composable
fun DiyHeader(
    ui: HomeUiState,
    onOpenGarage: () -> Unit,
    onAddVehicle: () -> Unit,
    onSelectVehicle: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val k = diyTokens()
    Row(modifier.fillMaxWidth().testTag(DIY_HEADER_TAG), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DiyDimens.S)) {
        Column(
            Modifier
                .weight(1f)
                .heightIn(min = DiyDimens.MinTouch)
                .clip(DiyDimens.ChipShape)
                .clickable(role = Role.Button, onClickLabel = if (ui.vehicle == null) "add a vehicle" else "open garage") {
                    if (ui.vehicle == null) onAddVehicle() else onOpenGarage()
                }
                .padding(horizontal = DiyDimens.Xs),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                proVehicleLine(ui.vehicle),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = k.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            val caption = if (ui.vehicle == null) "Tap to add your vehicle" else ui.vehicle.odometerMiles?.let { "%,d mi".format(Locale.US, it) }
            if (caption != null) Text(caption, style = MaterialTheme.typography.labelSmall, color = k.textSecondary)
        }
        if (ui.vehicles.size > 1) {
            var open by remember { mutableStateOf(false) }
            Box {
                DiyIconButton(PenelopeIcons.Garage, "Switch vehicle", { open = true })
                DropdownMenu(open, { open = false }) {
                    ui.vehicles.forEach { v ->
                        DropdownMenuItem(
                            text = { Text(v.title + if (v.id == ui.vehicle?.id) " (selected)" else "") },
                            onClick = { open = false; onSelectVehicle(v.id) },
                        )
                    }
                }
            }
        }
        DiyIconButton(Icons.Outlined.Settings, "Settings", onOpenSettings)
    }
}
