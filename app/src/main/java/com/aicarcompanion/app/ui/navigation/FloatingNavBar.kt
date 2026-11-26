package com.aicarcompanion.app.ui.navigation

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.semantics.contentDescription

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.unit.Dp
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
 * Floating pill navigation for the current level's [tabs]. Up to five tabs fit at 320dp and 200% text:
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
    BoxWithConstraints {
        // The selected tab gives up its extra label space first, so every tab keeps the 48 dp minimum.
        val selectedWeight = selectedTabWeight(maxWidth - NAV_HORIZONTAL_CHROME, tabs.size)
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
                        .weight(if (compactLabels && isSelected) selectedWeight else 1f)
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
}

/** Width share of the selected tab when only it shows its label. */
private const val SELECTED_TAB_WEIGHT = 2.8f
private val MIN_TAB_WIDTH = 48.dp
/** The bar's horizontal outer padding (12 dp each side) plus its inner padding (4 dp each side). */
private val NAV_HORIZONTAL_CHROME = 32.dp

/**
 * The selected tab's width share at large font sizes: [SELECTED_TAB_WEIGHT], reduced (never below an equal
 * share) when that would leave the other tabs narrower than 48 dp in [available] width. Before Phase 1D the
 * share was always 2.8, which made the unselected DIY tabs 42 dp wide on a 320 dp display at 200% font.
 */
internal fun selectedTabWeight(available: Dp, tabs: Int): Float {
    if (tabs <= 1) return SELECTED_TAB_WEIGHT
    return (available / MIN_TAB_WIDTH - (tabs - 1)).coerceIn(1f, SELECTED_TAB_WEIGHT)
}
