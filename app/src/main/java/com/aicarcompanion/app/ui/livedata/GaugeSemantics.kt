package com.aicarcompanion.app.ui.livedata

import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.domain.livedata.LiveSensor
import com.aicarcompanion.app.domain.livedata.SensorValueState
import com.aicarcompanion.app.domain.livedata.UnitChoice
import com.aicarcompanion.app.domain.livedata.UnitConversion
import com.aicarcompanion.app.ui.navigation.LiveDataStatus
import java.time.Instant

/** Words for gauge states and screen-reader descriptions. Pure, so it can be unit tested. */
object GaugeSemantics {
    const val SAMPLE_CHIP = "SAMPLE"

    /** The unit to show: the owner's choice if it fits this sensor, otherwise the sensor's own unit. */
    fun unitFor(sensor: LiveSensor, chosen: UnitChoice?): UnitChoice? =
        chosen?.takeIf { it.baseUnit == sensor.baseUnit } ?: UnitConversion.defaultFor(sensor.baseUnit)

    /** The number a gauge may show, or null when the state has no current value. */
    fun currentValue(state: SensorValueState): Double? = when (state) {
        is SensorValueState.Live -> state.value
        is SensorValueState.Sample -> state.value
        else -> null
    }

    /** Short text for a state that has no live number; null when the gauge shows a live or sample value. */
    fun stateText(state: SensorValueState, now: Instant): String? = when (state) {
        is SensorValueState.Live, is SensorValueState.Sample -> null
        is SensorValueState.Stale -> "Stale · ${LiveDataStatus.ageLabel(state.lastAt, now)}"
        SensorValueState.Unsupported -> "Not supported"
        is SensorValueState.TemporarilyUnavailable -> "No answer"
        SensorValueState.Disconnected -> "Disconnected"
        is SensorValueState.Paused -> "Paused: clearing codes"
    }

    fun valueText(sensor: LiveSensor, value: Double, unit: UnitChoice?): String =
        unit?.let { UnitConversion.format(value, it) } ?: "%.1f".format(value)

    /** "Coolant temperature, 92 degrees Celsius, live". */
    fun gaugeDescription(sensor: LiveSensor, state: SensorValueState, chosen: UnitChoice?, now: Instant): String {
        val unit = unitFor(sensor, chosen)
        val spoken = unit?.spoken.orEmpty()
        return when (state) {
            is SensorValueState.Live -> "${sensor.label}, ${valueText(sensor, state.value, unit)} $spoken, live".replace("  ", " ")
            is SensorValueState.Sample ->
                "${sensor.label}, ${valueText(sensor, state.value, unit)} $spoken, sample data, not from your vehicle".replace("  ", " ")
            is SensorValueState.Stale ->
                "${sensor.label}, stale, last value ${valueText(sensor, state.lastValue, unit)} $spoken, ${LiveDataStatus.ageLabel(state.lastAt, now)}"
                    .replace("  ", " ")
            SensorValueState.Unsupported -> "${sensor.label}, not supported by this vehicle"
            is SensorValueState.TemporarilyUnavailable -> "${sensor.label}, temporarily unavailable, ${state.reason}"
            SensorValueState.Disconnected -> "${sensor.label}, disconnected, no reading"
            is SensorValueState.Paused -> "${sensor.label}, paused while trouble codes are cleared, no reading"
        }
    }

    /** "Normal", "Low" or "High" against the sensor's conventional limits; null without a current value. */
    fun rangeStatus(sensor: LiveSensor, state: SensorValueState): String? {
        val v = currentValue(state) ?: return null
        return when {
            sensor.warnHigh != null && v > sensor.warnHigh -> "High"
            sensor.warnLow != null && v < sensor.warnLow -> "Low"
            else -> "Normal"
        }
    }

    /** What the banner above the workspace says, using only what the states and connection show. */
    fun bannerStatus(hasVehicle: Boolean, obd: ObdConnectionState, states: Map<Int, SensorValueState>): LiveDataStatus {
        if (!hasVehicle) return LiveDataStatus.NoVehicle
        if (states.values.any { it is SensorValueState.Sample }) return LiveDataStatus.Sample
        if (obd !is ObdConnectionState.Connected) return LiveDataStatus.Disconnected
        if (states.values.any { it is SensorValueState.Live }) return LiveDataStatus.Live
        val stale = states.values.filterIsInstance<SensorValueState.Stale>().maxOfOrNull { it.lastAt }
        return if (stale != null) LiveDataStatus.Stale(stale) else LiveDataStatus.WaitingForData
    }
}
