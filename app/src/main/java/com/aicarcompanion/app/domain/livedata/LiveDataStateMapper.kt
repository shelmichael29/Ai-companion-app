package com.aicarcompanion.app.domain.livedata

import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import java.time.Duration
import java.time.Instant

/** One answer from the poller for one sensor. */
sealed interface PidSample {
    data class Ok(val value: Double, val at: Instant) : PidSample
    /** Asked, but no usable answer. [lastGood] is the last real value, kept so it can be shown as stale. */
    data class NoAnswer(val at: Instant, val lastGood: Ok? = null) : PidSample
    data object Unsupported : PidSample
}

/** What the poller currently knows. [paused] is true while polling is held off (clearing codes). */
data class LivePollSnapshot(
    val perPid: Map<Int, PidSample> = emptyMap(),
    val paused: Boolean = false,
)

/** Turns raw poller output and connection state into an honest per-sensor state. Pure. */
object LiveDataStateMapper {
    const val PAUSED_REASON = "Paused while trouble codes are cleared"

    fun map(
        requested: Set<Int>,
        snapshot: LivePollSnapshot,
        connection: ObdConnectionState,
        now: Instant,
        demo: Boolean,
        sampleValue: (LiveSensor) -> Double = ::sampleValueFor,
    ): Map<Int, SensorValueState> = requested.associateWith { pid ->
        val sensor = LiveSensorCatalog.find(pid)
        when {
            demo && sensor != null -> SensorValueState.Sample(sampleValue(sensor), now)
            connection !is ObdConnectionState.Connected -> SensorValueState.Disconnected
            snapshot.paused -> SensorValueState.Paused(PAUSED_REASON)
            else -> fromSample(snapshot.perPid[pid], now, connection)
        }
    }

    private fun fromSample(sample: PidSample?, now: Instant, c: ObdConnectionState.Connected): SensorValueState = when (sample) {
        is PidSample.Unsupported -> SensorValueState.Unsupported
        is PidSample.Ok -> ageOf(sample, now)
        is PidSample.NoAnswer -> sample.lastGood?.let { ageOf(it, now, forceStale = true) }
            ?: SensorValueState.TemporarilyUnavailable("No answer from the vehicle")
        null -> SensorValueState.TemporarilyUnavailable(
            if (c.vehicleProtocol == null) "Vehicle not responding. Is the ignition on?" else "Waiting for the first reading",
        )
    }

    private fun ageOf(ok: PidSample.Ok, now: Instant, forceStale: Boolean = false): SensorValueState =
        if (forceStale || Duration.between(ok.at, now) > SensorValueState.STALE_AFTER) SensorValueState.Stale(ok.value, ok.at)
        else SensorValueState.Live(ok.value, ok.at)

    /** Demonstration values: a fixed point inside each sensor's scale. Only ever shown as SAMPLE. */
    fun sampleValueFor(sensor: LiveSensor): Double = when (sensor.pid) {
        0x0C -> 2_150.0
        0x0D -> 72.0
        0x05 -> 92.0
        0x42 -> 14.1
        else -> sensor.min + (sensor.max - sensor.min) * 0.4
    }
}
