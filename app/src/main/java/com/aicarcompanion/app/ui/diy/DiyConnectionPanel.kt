package com.aicarcompanion.app.ui.diy

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.dashboard.DiyConnectionStage
import com.aicarcompanion.app.dashboard.DiyConnectionStatus
import com.aicarcompanion.app.dashboard.StepState
import com.aicarcompanion.app.ui.design.PenelopeIcons
import com.aicarcompanion.app.ui.navigation.LiveDataStatus
import java.time.Instant

const val DIY_CONNECTION_TAG = "diy_connection"

/** Which of the three steps a row describes. */
internal enum class ConnectionStep(val title: String, val icon: ImageVector) {
    BLUETOOTH("Bluetooth link", PenelopeIcons.Link),
    ADAPTER("Adapter setup", PenelopeIcons.Plug),
    ECU("Vehicle ECU", PenelopeIcons.Engine),
}

internal fun DiyConnectionStatus.stateOf(step: ConnectionStep): StepState = when (step) {
    ConnectionStep.BLUETOOTH -> link
    ConnectionStep.ADAPTER -> adapterStep
    ConnectionStep.ECU -> ecuStep
}

/** The words for a step. "Responding" is only ever said for a vehicle ECU that has really answered. */
internal fun stepWord(step: ConnectionStep, state: StepState, stage: DiyConnectionStage): String = when (step) {
    ConnectionStep.BLUETOOTH -> when (state) {
        StepState.DONE -> "Connected"
        StepState.ACTIVE -> "Connecting"
        StepState.PENDING -> "Not connected"
        StepState.FAILED -> "Failed"
    }
    ConnectionStep.ADAPTER -> when (state) {
        StepState.DONE -> "Ready"
        StepState.ACTIVE -> "Setting up"
        StepState.PENDING -> "Not set up"
        StepState.FAILED -> "Problem"
    }
    ConnectionStep.ECU -> when (state) {
        StepState.DONE -> "Responding"
        StepState.ACTIVE -> "Checking"
        StepState.PENDING -> if (stage == DiyConnectionStage.ADAPTER_READY) "No answer yet" else "Waiting"
        StepState.FAILED -> if (stage == DiyConnectionStage.STALE) "Stale" else "Problem"
    }
}

internal fun DiyConnectionStage.headline(): String = when (this) {
    DiyConnectionStage.DISCONNECTED -> "Not connected"
    DiyConnectionStage.BLUETOOTH_CONNECTED -> "Bluetooth connected"
    DiyConnectionStage.ADAPTER_READY -> "Adapter ready"
    DiyConnectionStage.ECU_RESPONDING -> "Vehicle responding"
    DiyConnectionStage.STALE -> "Data stale"
    DiyConnectionStage.ERROR -> "Connection problem"
}

private fun StepState.icon(): ImageVector = when (this) {
    StepState.DONE -> PenelopeIcons.Check
    StepState.ACTIVE -> PenelopeIcons.Wave
    StepState.PENDING -> PenelopeIcons.More
    StepState.FAILED -> PenelopeIcons.Alert
}

@Composable
private fun StepState.color(): Color {
    val k = diyTokens()
    return when (this) {
        StepState.DONE -> k.ok
        StepState.ACTIVE -> k.accent
        StepState.PENDING -> k.textSecondary
        StepState.FAILED -> k.fault
    }
}

@Composable
private fun DiyConnectionStage.color(): Color {
    val k = diyTokens()
    return when (this) {
        DiyConnectionStage.ECU_RESPONDING -> k.ok
        DiyConnectionStage.STALE -> k.warn
        DiyConnectionStage.ERROR -> k.fault
        else -> k.textSecondary
    }
}

/**
 * Bluetooth, adapter and vehicle ECU as three separate steps. Only a step that has really happened
 * is ticked; a Bluetooth link alone leaves "Vehicle ECU" waiting.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiyConnectionPanel(
    status: DiyConnectionStatus,
    now: Instant,
    onOpenAdapter: () -> Unit,
    onOpenConnectionSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val k = diyTokens()
    DiyPanel(modifier.testTag(DIY_CONNECTION_TAG)) {
        DiySectionHeader("Connection") {
            DiyStatusTag(
                status.stage.headline(),
                status.stage.color(),
                when (status.stage) {
                    DiyConnectionStage.ECU_RESPONDING -> PenelopeIcons.Check
                    DiyConnectionStage.STALE -> PenelopeIcons.Alert
                    DiyConnectionStage.ERROR -> PenelopeIcons.Alert
                    else -> PenelopeIcons.Plug
                },
            )
        }
        val stacked = LocalDensity.current.fontScale > 1.3f
        val steps = ConnectionStep.entries
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(DiyDimens.S)) {
                steps.forEach { Step(it, status, Modifier.fillMaxWidth(), horizontal = true) }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DiyDimens.S)) {
                steps.forEach { Step(it, status, Modifier.weight(1f), horizontal = false) }
            }
        }
        Text(
            status.linkLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = k.textPrimary,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        )
        val details = listOfNotNull(
            status.protocol?.let { "Protocol: $it" },
            status.adapter?.let { "Adapter: $it" },
            status.lastDataAt?.let { "Last data: ${LiveDataStatus.ageLabel(it, now)}" },
        )
        if (details.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                details.forEach { Text(it, style = MaterialTheme.typography.labelMedium, color = k.textSecondary) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(DiyDimens.S)) {
            DiyTextButton("Choose adapter", onOpenAdapter, icon = PenelopeIcons.Plug)
            DiyTextButton("Connection settings", onOpenConnectionSettings, icon = PenelopeIcons.Link)
        }
    }
}

@Composable
private fun Step(step: ConnectionStep, status: DiyConnectionStatus, modifier: Modifier, horizontal: Boolean) {
    val k = diyTokens()
    val state = status.stateOf(step)
    val word = stepWord(step, state, status.stage)
    val color = state.color()
    val icon = @Composable {
        Box(
            Modifier.size(28.dp).clip(CircleShape).border(1.5.dp, color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(state.icon(), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        }
    }
    val texts = @Composable {
        Column {
            Text(step.title, style = MaterialTheme.typography.labelMedium, color = k.textSecondary)
            Text(word, style = MaterialTheme.typography.labelLarge, color = k.textPrimary)
        }
    }
    val spoken = "${step.title}: $word"
    if (horizontal) {
        Row(
            modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DiyDimens.M),
        ) { icon(); texts() }
    } else {
        Column(
            modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
            verticalArrangement = Arrangement.spacedBy(DiyDimens.Xs + 2.dp),
        ) { icon(); texts() }
    }
}
