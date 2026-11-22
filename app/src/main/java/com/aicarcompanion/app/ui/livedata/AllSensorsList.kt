package com.aicarcompanion.app.ui.livedata

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.domain.livedata.LiveSensor
import com.aicarcompanion.app.domain.livedata.LiveSensorCatalog
import com.aicarcompanion.app.domain.livedata.SensorValueState
import java.time.Instant

/** Every sensor Penelope knows, supported ones first. Unsupported sensors are listed, never hidden or faked. */
internal fun LazyGridScope.allSensorItems(ui: LiveDataUiState, c: LiveColors, now: Instant) {
    val unsupported = { s: LiveSensor -> ui.states[s.pid] is SensorValueState.Unsupported }
    val ordered = LiveSensorCatalog.all.sortedBy { unsupported(it) } // stable: keeps catalog order inside each group
    items(ordered, key = { "all_${it.pid}" }, span = { GridItemSpan(maxLineSpan) }) { sensor ->
        val state = ui.states[sensor.pid] ?: SensorValueState.TemporarilyUnavailable("Waiting for the first reading")
        SensorRow(sensor, state, c, now)
    }
}

@Composable
private fun SensorRow(sensor: LiveSensor, state: SensorValueState, c: LiveColors, now: Instant) {
    val unit = GaugeSemantics.unitFor(sensor, null)
    val value = GaugeSemantics.currentValue(state)
    val shown = value?.let { "${GaugeSemantics.valueText(sensor, it, unit)} ${unit?.symbol.orEmpty()}".trim() }
        ?: GaugeSemantics.stateText(state, now).orEmpty()
    val origin = when (state) {
        is SensorValueState.Sample -> GaugeSemantics.SAMPLE_CHIP
        is SensorValueState.Live, is SensorValueState.Stale -> "Vehicle"
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().testTag("sensor_${sensor.pid}")
            .background(c.panel, RoundedCornerShape(if (c.plain) 6.dp else 14.dp))
            .heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = GaugeSemantics.gaugeDescription(sensor, state, null, now) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(sensor.label, style = MaterialTheme.typography.titleSmall, color = c.primary)
            if (origin != null) Text(origin, style = MaterialTheme.typography.labelMedium, color = if (origin == GaugeSemantics.SAMPLE_CHIP) c.warn else c.secondary)
        }
        Text(
            shown, style = MaterialTheme.typography.titleMedium,
            color = if (value != null) c.primary else c.secondary, maxLines = 2,
        )
    }
}
