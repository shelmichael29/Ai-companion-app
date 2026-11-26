package com.aicarcompanion.app.ui.diy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.domain.livedata.GaugeSpec
import com.aicarcompanion.app.domain.livedata.LiveSensor
import com.aicarcompanion.app.domain.livedata.LiveSensorCatalog
import com.aicarcompanion.app.domain.livedata.SensorValueState
import com.aicarcompanion.app.ui.design.PenelopeIcons
import com.aicarcompanion.app.ui.livedata.GaugeSemantics
import java.time.Instant

const val DIY_LIVE_TAG = "diy_live"
internal const val LIVE_STRIP_GAUGES = 4
private const val WAITING = "Waiting for the first reading"

/**
 * What the Overview's live strip needs from the Live Data workspace: the vehicle's own gauge layout
 * and each sensor's honest state. It reads what Live Data already requests; it asks the car for nothing extra.
 */
data class DiyLiveInput(
    val hasVehicle: Boolean,
    val gauges: List<GaugeSpec>,
    val states: Map<Int, SensorValueState>,
    /** The vehicle is known but its Live Data layout has not loaded yet (cold start): say so instead of claiming no gauges. */
    val loading: Boolean = false,
)

/**
 * The strip's input from the Live Data state. The home screen already knows the vehicle before the Live Data
 * view model has emitted its layout, so a known vehicle with no layout yet is "loading", never "no vehicle".
 */
internal fun diyLiveInputFor(state: com.aicarcompanion.app.ui.livedata.LiveDataUiState, homeHasVehicle: Boolean): DiyLiveInput {
    val layoutReady = state.hasVehicle && state.active != null
    return DiyLiveInput(
        hasVehicle = state.hasVehicle || homeHasVehicle,
        gauges = state.active?.gauges.orEmpty(),
        states = state.states,
        loading = homeHasVehicle && !layoutReady,
    )
}

/**
 * The first gauges of the active Live Data layout. A number appears only for a live value, a stale
 * last value (labeled stale and how old), or a labeled sample value. Everything else is written in words.
 */
@Composable
fun DiyLiveStrip(
    live: DiyLiveInput?,
    now: Instant,
    onOpenLiveData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val k = diyTokens()
    DiyPanel(modifier.testTag(DIY_LIVE_TAG)) {
        DiySectionHeader("Live data") {
            DiyTextButton("Open Live Data", onOpenLiveData, icon = PenelopeIcons.ChevronRight)
        }
        val tiles = live?.takeIf { it.hasVehicle }?.gauges.orEmpty()
            .mapNotNull { spec -> LiveSensorCatalog.find(spec.pid)?.let { spec to it } }
            .take(LIVE_STRIP_GAUGES)
        when {
            live == null -> EmptyNote("Open Live Data to see readings", onOpenLiveData)
            live.loading -> Text("Waiting for the first reading", style = MaterialTheme.typography.bodyMedium, color = k.textSecondary)
            !live.hasVehicle -> Text("Add a vehicle to see live readings.", style = MaterialTheme.typography.bodyMedium, color = k.textSecondary)
            tiles.isEmpty() -> EmptyNote("No gauges chosen yet. Choose them in Live Data.", onOpenLiveData)
            else -> {
                val columns = if (LocalDensity.current.fontScale > 1.5f) 1 else 2
                Column(verticalArrangement = Arrangement.spacedBy(DiyDimens.S)) {
                    tiles.chunked(columns).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DiyDimens.S)) {
                            row.forEach { (spec, sensor) ->
                                Tile(spec, sensor, live.states[spec.pid] ?: SensorValueState.TemporarilyUnavailable(WAITING), now, onOpenLiveData, Modifier.weight(1f))
                            }
                            if (row.size < columns) Box(Modifier.weight((columns - row.size).toFloat()))
                        }
                    }
                }
                DiyTextButton("Edit gauges", onOpenLiveData, icon = PenelopeIcons.Tune)
            }
        }
    }
}

@Composable
private fun EmptyNote(text: String, onClick: () -> Unit) {
    val k = diyTokens()
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = k.textSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DiyDimens.MinTouch)
            .clip(DiyDimens.ChipShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = DiyDimens.M),
    )
}

@Composable
private fun Tile(
    spec: GaugeSpec,
    sensor: LiveSensor,
    state: SensorValueState,
    now: Instant,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val k = diyTokens()
    val unit = GaugeSemantics.unitFor(sensor, spec.unit)
    val shownValue = when (state) {
        is SensorValueState.Live -> state.value
        is SensorValueState.Sample -> state.value
        is SensorValueState.Stale -> state.lastValue
        else -> null
    }
    val note = when (state) {
        is SensorValueState.Sample -> "Sample data"
        else -> GaugeSemantics.stateText(state, now)
    }
    val noteColor = when (state) {
        is SensorValueState.Stale, is SensorValueState.Sample -> k.warn
        else -> k.textSecondary
    }
    val valueColor = if (state is SensorValueState.Stale) k.textSecondary else k.textPrimary
    Column(
        modifier
            .heightIn(min = DiyDimens.MinTouch)
            .clip(DiyDimens.ChipShape)
            .background(k.raised)
            .border(1.dp, k.hairline, DiyDimens.ChipShape)
            .clickable(role = Role.Button, onClickLabel = "open Live Data", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = GaugeSemantics.gaugeDescription(sensor, state, spec.unit, now) }
            .padding(horizontal = DiyDimens.M, vertical = DiyDimens.S),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(sensor.label, style = MaterialTheme.typography.labelMedium, color = k.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(DiyDimens.Xs)) {
            Text(
                if (shownValue != null) GaugeSemantics.valueText(sensor, shownValue, unit) else "—",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                color = valueColor,
                maxLines = 1,
            )
            if (shownValue != null && unit != null) {
                Text(unit.symbol, style = MaterialTheme.typography.labelMedium, color = k.textSecondary, maxLines = 1, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
        if (note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = noteColor, maxLines = 2)
    }
}
