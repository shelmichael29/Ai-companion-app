package com.aicarcompanion.app.domain.model

/** A user's vehicle profile. Identity fields (year/make/model/trim/engine) drive future lookups. */
data class Vehicle(
    val id: String,
    val year: Int,
    val make: String,
    val model: String,
    val trim: String? = null,
    val engine: EngineSpec,
    val vin: String? = null,
    val odometerMiles: Int? = null,
    val nickname: String? = null,
) {
    /** e.g. "2018 Honda Civic EX". */
    val displayName: String
        get() = listOfNotNull(year.toString(), make, model, trim?.takeIf { it.isNotBlank() })
            .joinToString(" ")

    /** Nickname if set, otherwise [displayName]. */
    val title: String
        get() = nickname?.takeIf { it.isNotBlank() } ?: displayName
}

data class EngineSpec(
    val displacementLiters: Double? = null,
    val cylinders: Int? = null,
    val fuelType: FuelType = FuelType.GASOLINE,
    val aspiration: Aspiration = Aspiration.NATURALLY_ASPIRATED,
) {
    /** e.g. "2.0L I4 Gasoline" or "Electric". */
    val description: String
        get() {
            if (fuelType == FuelType.ELECTRIC) return FuelType.ELECTRIC.label
            val parts = mutableListOf<String>()
            displacementLiters?.let { parts += "%.1fL".format(it) }
            cylinders?.let { parts += layoutFor(it) }
            if (aspiration != Aspiration.NATURALLY_ASPIRATED) parts += aspiration.label
            parts += fuelType.label
            return parts.joinToString(" ")
        }

    private fun layoutFor(cylinders: Int): String = when (cylinders) {
        in 1..5 -> "I$cylinders"
        else -> "V$cylinders"
    }
}

enum class FuelType(val label: String) {
    GASOLINE("Gasoline"),
    DIESEL("Diesel"),
    HYBRID("Hybrid"),
    PLUG_IN_HYBRID("Plug-in hybrid"),
    ELECTRIC("Electric"),
    FLEX_FUEL("Flex fuel"),
}

enum class Aspiration(val label: String) {
    NATURALLY_ASPIRATED("Naturally aspirated"),
    TURBOCHARGED("Turbo"),
    SUPERCHARGED("Supercharged"),
}
