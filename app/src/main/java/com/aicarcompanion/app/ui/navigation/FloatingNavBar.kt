package com.aicarcompanion.app.ui.navigation

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.semantics.contentDescription

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
internal fun FloatingNavBar(
    tabs: List<NavTab>,
    selectedRoute: String?,
    /** Advanced / Professional uses the plain black-and-grey style; the other levels the Penelope style. */
    plain: Boolean = false,
    onSelect: (NavTab) -> Unit,
) {
    val t = Penelope.tokens
    val k = com.aicarcompanion.app.ui.home.advancedTokens()
    val shape = RoundedCornerShape(if (plain) 16.dp else 32.dp)
    // At large font sizes only the selected tab shows its label, so no label is cut off; TalkBack reads all of them.
    val compactLabels = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .fillMaxWidth()
            .then(
                if (plain) {
                    Modifier.clip(shape).background(k.panel).border(1.dp, k.hairline, shape)
                } else {
                    Modifier
                        .shadow(18.dp, shape, ambientColor = t.brandColors[2].copy(alpha = 0.25f), spotColor = t.brandColors[1].copy(alpha = 0.25f))
                        .clip(shape)
                        .background(t.glassStrong)
                        .border(1.dp, t.glassBorder, shape)
                },
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        tabs.forEach { tab ->
            val isSelected = tab.route == selectedRoute
            val selectedTint = if (plain) k.primary else Color.White
            val idleTint = if (plain) k.secondary else t.textSecondary
            val tint by animateColorAsState(if (isSelected) selectedTint else idleTint, label = "tabTint")
            val showLabel = !compactLabels || isSelected
            Column(
                Modifier
                    .weight(if (compactLabels && isSelected) 2.8f else 1f)
                    .heightIn(min = 60.dp)
                    .clip(RoundedCornerShape(if (plain) 12.dp else 26.dp))
                    .then(
                        when {
                            !isSelected -> Modifier
                            plain -> Modifier.background(k.raised)
                            else -> Modifier.background(t.brandGradient)
                        },
                    )
                    .semantics { contentDescription = tab.label }
                    .selectable(isSelected, role = Role.Tab, onClick = { onSelect(tab) })
                    .padding(vertical = 8.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(tab.destination.icon(), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                if (showLabel) Text(
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
