package com.aicarcompanion.app.assistant.conversation

import com.aicarcompanion.app.diagnostics.obd.ObdPid
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Formats values for speech and display. U.S. units first, metric in parentheses. */
object SpokenFormat {

    fun liveValue(pid: ObdPid, value: Double): String = when (pid) {
        ObdPid.COOLANT_TEMP, ObdPid.INTAKE_AIR_TEMP ->
            "${celsiusToF(value).roundToInt()} degrees Fahrenheit (${value.roundToInt()} °C)"
        ObdPid.VEHICLE_SPEED -> "${(value * 0.621371).roundToInt()} miles per hour (${value.roundToInt()} km/h)"
        ObdPid.ENGINE_RPM -> "${value.roundToInt()} RPM"
        ObdPid.CONTROL_MODULE_VOLTAGE -> String.format(Locale.US, "%.1f volts", value)
        ObdPid.ENGINE_LOAD, ObdPid.THROTTLE_POSITION, ObdPid.FUEL_LEVEL -> "${value.roundToInt()} percent"
        ObdPid.SHORT_TERM_FUEL_TRIM_B1, ObdPid.LONG_TERM_FUEL_TRIM_B1,
        ObdPid.SHORT_TERM_FUEL_TRIM_B2, ObdPid.LONG_TERM_FUEL_TRIM_B2 -> String.format(Locale.US, "%+.1f percent", value)
        ObdPid.MAF_RATE -> String.format(Locale.US, "%.1f grams per second", value)
        ObdPid.INTAKE_MAP -> "${value.roundToInt()} kPa"
    }

    fun celsiusToF(c: Double) = c * 9 / 5 + 32

    /** Speaks "P0420" as "P 0 4 2 0" so TTS engines read each character. */
    fun code(code: String): String = code.toCharArray().joinToString(" ")

    fun dateTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("MMMM d 'at' h:mm a", Locale.US).withZone(zone).format(instant)

    fun miles(miles: Int): String = String.format(Locale.US, "%,d miles", miles)

    fun list(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + ", and " + items.last()
    }
}
