package com.aicarcompanion.app.domain.livedata

import java.time.Duration
import java.time.Instant

/**
 * One standard OBD-II Mode 01 sensor the Live Data workspace can show. [min] and [max] are the
 * gauge scale in [baseUnit]; [warnLow] and [warnHigh] are conventional "look at this" limits, not
 * a diagnosis. The values here describe the sensor only; a value is never invented.
 */
data class LiveSensor(
    val pid: Int,
    val label: String,
    val baseUnit: String,
    val min: Double,
    val max: Double,
    val warnLow: Double? = null,
    val warnHigh: Double? = null,
)

object LiveSensorCatalog {
    /** Most useful first. The poller reads in this order. */
    val all: List<LiveSensor> = listOf(
        LiveSensor(0x0C, "Engine speed", "rpm", 0.0, 8_000.0, warnHigh = 6_500.0),
        LiveSensor(0x0D, "Vehicle speed", "km/h", 0.0, 260.0),
        LiveSensor(0x05, "Coolant temperature", "°C", 0.0, 130.0, warnHigh = 110.0),
        LiveSensor(0x42, "Control module voltage", "V", 8.0, 16.0, warnLow = 11.8, warnHigh = 15.0),
        LiveSensor(0x2F, "Fuel level", "%", 0.0, 100.0, warnLow = 10.0),
        LiveSensor(0x04, "Calculated engine load", "%", 0.0, 100.0),
        LiveSensor(0x11, "Throttle position", "%", 0.0, 100.0),
        LiveSensor(0x0F, "Intake air temperature", "°C", -20.0, 80.0),
        LiveSensor(0x06, "Short-term fuel trim (bank 1)", "%", -25.0, 25.0, warnLow = -20.0, warnHigh = 20.0),
        LiveSensor(0x07, "Long-term fuel trim (bank 1)", "%", -25.0, 25.0, warnLow = -20.0, warnHigh = 20.0),
        LiveSensor(0x08, "Short-term fuel trim (bank 2)", "%", -25.0, 25.0, warnLow = -20.0, warnHigh = 20.0),
        LiveSensor(0x09, "Long-term fuel trim (bank 2)", "%", -25.0, 25.0, warnLow = -20.0, warnHigh = 20.0),
        LiveSensor(0x10, "Mass air flow (MAF)", "g/s", 0.0, 250.0),
        LiveSensor(0x0B, "Intake manifold pressure (MAP)", "kPa", 0.0, 255.0),
    )

    private val byPid = all.associateBy { it.pid }
    fun find(pid: Int): LiveSensor? = byPid[pid]

    /** The gauges a vehicle starts with before the owner changes anything. */
    val defaultPids: List<Int> = listOf(0x0C, 0x0D, 0x05, 0x42)
}

/** What can honestly be said about one sensor right now. Never carries a made-up number. */
sealed interface SensorValueState {
    /** A reading from the vehicle, no older than [STALE_AFTER]. */
    data class Live(val value: Double, val at: Instant) : SensorValueState

    /** The last real reading, older than [STALE_AFTER]. */
    data class Stale(val lastValue: Double, val lastAt: Instant) : SensorValueState

    /** The vehicle does not report this sensor. */
    data object Unsupported : SensorValueState

    /** Supported, but the vehicle gave no usable answer this time (or none yet). */
    data class TemporarilyUnavailable(val reason: String) : SensorValueState

    data object Disconnected : SensorValueState

    /** Polling is held off on purpose, for example while trouble codes are being cleared. */
    data class Paused(val reason: String) : SensorValueState

    /** Built-in demonstration value. Not from any vehicle. */
    data class Sample(val value: Double, val at: Instant) : SensorValueState

    companion object {
        val STALE_AFTER: Duration = Duration.ofSeconds(10)
    }
}
