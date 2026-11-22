package com.aicarcompanion.app.ui.livedata

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicarcompanion.app.domain.livedata.GaugeSize
import com.aicarcompanion.app.domain.livedata.GaugeSpec
import com.aicarcompanion.app.domain.livedata.GaugeStyle
import com.aicarcompanion.app.domain.livedata.LiveSensor
import com.aicarcompanion.app.domain.livedata.SensorValueState
import java.time.Instant
import kotlin.math.cos
import kotlin.math.sin

/**
 * One gauge. The same state always reads the same way in every style: a number appears only for a
 * live or sample value; every other state is written in words and never drawn as 0 or a frozen needle.
 */
@Composable
fun GaugeCard(
    sensor: LiveSensor,
    spec: GaugeSpec,
    state: SensorValueState,
    history: List<Double>,
    colors: LiveColors,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    val unit = GaugeSemantics.unitFor(sensor, spec.unit)
    val description = GaugeSemantics.gaugeDescription(sensor, state, spec.unit, now)
    val value = GaugeSemantics.currentValue(state)
    val range = GaugeSemantics.rangeStatus(sensor, state)
    val valueColor = if (range == "Normal" || range == null) colors.primary else colors.warn
    val valueText = value?.let { GaugeSemantics.valueText(sensor, it, unit) }
    val stateText = GaugeSemantics.stateText(state, now)
    val sp = when (spec.size) { GaugeSize.SMALL -> 30; GaugeSize.MEDIUM -> 34; GaugeSize.LARGE -> 48 }
    val shape = RoundedCornerShape(if (colors.plain) 6.dp else 18.dp)

    Column(
        modifier
            .testTag("gauge_${sensor.pid}")
            .semantics(mergeDescendants = true) { contentDescription = description }
            .clip(shape)
            .background(colors.panel)
            .border(1.dp, colors.hairline, shape)
            .heightIn(min = (112 * spec.size.rows).dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(sensor.label, style = MaterialTheme.typography.labelLarge, color = colors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        when (spec.style) {
            GaugeStyle.ANALOG -> AnalogDial(sensor, value, valueColor, colors, spec.size)
            GaugeStyle.LINE_GRAPH -> LineGraph(sensor, history, colors, spec.size)
            else -> Unit
        }
        if (valueText != null) {
            val numeric = spec.style == GaugeStyle.NUMERIC
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    valueText,
                    color = valueColor, maxLines = 1, softWrap = false,
                    fontSize = (if (numeric) sp + 8 else sp).sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = if (spec.style == GaugeStyle.DIGITAL) FontFamily.Monospace else FontFamily.Default,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(unit?.symbol.orEmpty(), style = MaterialTheme.typography.titleMedium, color = colors.secondary, maxLines = 1, softWrap = false)
            }
        } else if (stateText != null) {
            Text(stateText, style = MaterialTheme.typography.titleMedium, color = colors.primary, maxLines = 2)
        }
        if (state is SensorValueState.Stale) {
            // The last real value, clearly dimmed and labelled; never shown as current.
            val last = GaugeSemantics.valueText(sensor, state.lastValue, unit)
            Text("Last: $last ${unit?.symbol.orEmpty()}", style = MaterialTheme.typography.bodyMedium, color = colors.secondary, maxLines = 1)
        }
        if (spec.style == GaugeStyle.DIGITAL && value != null) RangeBar(sensor, value, valueColor, colors)
        if (spec.style == GaugeStyle.STATUS && range != null) Text("Status: $range", style = MaterialTheme.typography.titleSmall, color = valueColor)
        if (state is SensorValueState.Sample) {
            Text(
                GaugeSemantics.SAMPLE_CHIP, style = MaterialTheme.typography.labelMedium, color = colors.warn,
                modifier = Modifier.border(1.dp, colors.warn, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    }
}

private fun fraction(sensor: LiveSensor, value: Double): Float =
    ((value - sensor.min) / (sensor.max - sensor.min)).coerceIn(0.0, 1.0).toFloat()

@Composable
private fun RangeBar(sensor: LiveSensor, value: Double, color: Color, colors: LiveColors) {
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(colors.hairline)) {
        Box(Modifier.fillMaxWidth(fraction(sensor, value)).height(8.dp).background(color))
    }
}

@Composable
private fun AnalogDial(sensor: LiveSensor, value: Double?, color: Color, colors: LiveColors, size: GaugeSize) {
    val track = colors.hairline
    val dialHeight = if (size == GaugeSize.LARGE) 150.dp else 84.dp
    Canvas(Modifier.fillMaxWidth().height(dialHeight)) {
        val stroke = 10.dp.toPx()
        val diameter = minOf(this.size.width, this.size.height) - stroke
        val topLeft = Offset((this.size.width - diameter) / 2f, stroke / 2f)
        val arcSize = Size(diameter, diameter)
        drawArc(track, 150f, 240f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        if (value != null) {
            val sweep = 240f * fraction(sensor, value)
            drawArc(color, 150f, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val angle = Math.toRadians((150f + sweep).toDouble())
            val centre = Offset(topLeft.x + diameter / 2f, topLeft.y + diameter / 2f)
            val r = diameter / 2f - stroke * 1.6f
            drawLine(colors.primary, centre, Offset(centre.x + (r * cos(angle)).toFloat(), centre.y + (r * sin(angle)).toFloat()), 4.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
private fun LineGraph(sensor: LiveSensor, history: List<Double>, colors: LiveColors, size: GaugeSize) {
    val height = if (size == GaugeSize.LARGE) 120.dp else 56.dp
    Canvas(Modifier.fillMaxWidth().height(height)) {
        drawLine(colors.hairline, Offset(0f, this.size.height), Offset(this.size.width, this.size.height), 1.dp.toPx())
        if (history.size < 2) return@Canvas // never draw a line through fewer than two real readings
        val path = Path()
        history.forEachIndexed { i, v ->
            val x = this.size.width * i / (history.size - 1)
            val y = this.size.height * (1f - fraction(sensor, v))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, colors.accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}
