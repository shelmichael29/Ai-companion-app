package com.aicarcompanion.app.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.ui.design.PenelopeIcons
import com.aicarcompanion.app.ui.theme.Penelope

internal fun Destination.icon(): ImageVector = when (this) {
    Destination.HOME -> PenelopeIcons.Home
    Destination.DRIVE -> PenelopeIcons.Drive
    Destination.MY_VEHICLE, Destination.GARAGE -> PenelopeIcons.Garage
    Destination.DIAGNOSTICS -> PenelopeIcons.Wrench
    Destination.LIVE_DATA -> PenelopeIcons.Gauge
    Destination.MORE -> PenelopeIcons.More
}

/**
 * Floating pill navigation for the current level's [tabs]. Up to five tabs fit at 360dp and 200% text:
 * labels are single-line and ellipsized (the full label is still what TalkBack reads), and every tab is at
 * least 48dp wide and tall.
 */
@Composable
internal fun FloatingNavBar(tabs: List<NavTab>, selectedRoute: String?, onSelect: (NavTab) -> Unit) {
    val t = Penelope.tokens
    val shape = RoundedCornerShape(32.dp)
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .fillMaxWidth()
            .shadow(18.dp, shape, ambientColor = t.brandColors[2].copy(alpha = 0.25f), spotColor = t.brandColors[1].copy(alpha = 0.25f))
            .clip(shape)
            .background(t.glassStrong)
            .border(1.dp, t.glassBorder, shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        tabs.forEach { tab ->
            val isSelected = tab.route == selectedRoute
            val tint by animateColorAsState(if (isSelected) Color.White else t.textSecondary, label = "tabTint")
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 60.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .then(if (isSelected) Modifier.background(t.brandGradient) else Modifier)
                    .selectable(isSelected, role = Role.Tab, onClick = { onSelect(tab) })
                    .padding(vertical = 8.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(tab.destination.icon(), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
