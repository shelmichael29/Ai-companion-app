package com.aicarcompanion.app.domain.livedata

/** A unit a sensor can be shown in. [symbol] is what appears on screen. */
enum class UnitChoice(val symbol: String, val spoken: String, val baseUnit: String) {
    CELSIUS("°C", "degrees Celsius", "°C"),
    FAHRENHEIT("°F", "degrees Fahrenheit", "°C"),
    KMH("km/h", "kilometres per hour", "km/h"),
    MPH("mph", "miles per hour", "km/h"),
    KPA("kPa", "kilopascals", "kPa"),
    PSI("psi", "pounds per square inch", "kPa"),
    BAR("bar", "bar", "kPa"),
    GRAMS_PER_SECOND("g/s", "grams per second", "g/s"),
    POUNDS_PER_MINUTE("lb/min", "pounds per minute", "g/s"),
    VOLTS("V", "volts", "V"),
    PERCENT("%", "percent", "%"),
    RPM("rpm", "revolutions per minute", "rpm"),
}

object UnitConversion {
    /** Units offered for a sensor whose raw value is in [baseUnit]; the first is the default. */
    fun choicesFor(baseUnit: String): List<UnitChoice> = UnitChoice.entries.filter { it.baseUnit == baseUnit }
        .sortedBy { if (it.symbol == baseUnit) 0 else 1 }

    fun defaultFor(baseUnit: String): UnitChoice? = choicesFor(baseUnit).firstOrNull()

    /** Converts a raw value (in the unit's base unit) to [unit]. */
    fun convert(value: Double, unit: UnitChoice): Double = when (unit) {
        UnitChoice.FAHRENHEIT -> value * 9.0 / 5.0 + 32.0
        UnitChoice.MPH -> value * 0.621371
        UnitChoice.PSI -> value * 0.145038
        UnitChoice.BAR -> value / 100.0
        UnitChoice.POUNDS_PER_MINUTE -> value * 0.00132277
        else -> value
    }

    /** Text such as "92" or "13.8", with a precision that suits the unit. */
    fun format(value: Double, unit: UnitChoice): String {
        val v = convert(value, unit)
        return when {
            unit == UnitChoice.BAR || unit == UnitChoice.POUNDS_PER_MINUTE -> "%.2f".format(v)
            unit == UnitChoice.VOLTS || unit == UnitChoice.GRAMS_PER_SECOND || unit == UnitChoice.PERCENT || unit == UnitChoice.PSI ->
                "%.1f".format(v)
            else -> Math.round(v).toString()
        }
    }
}
