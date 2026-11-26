package com.aicarcompanion.app.ui.diy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.aicarcompanion.app.dashboard.DashboardItem
import com.aicarcompanion.app.ui.design.PenelopeIcons
import com.aicarcompanion.app.ui.home.HomeShortcut

const val DIY_SHORTCUTS_TAG = "diy_shortcuts"

private fun DashboardItem.icon() = when (this) {
    DashboardItem.CAR_HEALTH -> PenelopeIcons.Engine
    DashboardItem.MY_VEHICLE -> PenelopeIcons.Garage
    DashboardItem.HISTORY -> PenelopeIcons.History
    DashboardItem.SETTINGS -> PenelopeIcons.Tune
    DashboardItem.ADAPTER -> PenelopeIcons.Plug
    DashboardItem.CONNECTION_SETTINGS -> PenelopeIcons.Link
    DashboardItem.MILEAGE_LOG -> PenelopeIcons.Steps
    DashboardItem.EXTRAS -> PenelopeIcons.Grid
}

/**
 * The shortcuts the owner's Easy or Customizable home layout chose, in that order, plus Drive.
 * The same layout editor in Settings controls them; nothing here has its own storage.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DiyShortcuts(
    shortcuts: List<HomeShortcut>,
    onOpenDrive: () -> Unit,
    onCustomize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DiyPanel(modifier.testTag(DIY_SHORTCUTS_TAG)) {
        DiySectionHeader("Tools and shortcuts") {
            DiyTextButton("Customize", onCustomize, icon = PenelopeIcons.Tune)
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DiyDimens.S),
            verticalArrangement = Arrangement.spacedBy(DiyDimens.S),
        ) {
            shortcuts.forEach { s -> DiyChip(s.label, s.spoken, s.onClick, icon = s.item.icon()) }
            DiyChip("Drive", "Drive, hands-free tools for the road", onOpenDrive, icon = PenelopeIcons.Drive)
        }
    }
}
