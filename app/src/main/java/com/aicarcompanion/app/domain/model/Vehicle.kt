package com.aicarcompanion.app.domain.model

/** A user's vehicle profile. Identity fields (year/make/model/trim/engine) drive future lookups. */
data class Vehicle(
    val id: String,
    val year: Int,
    val make: String,
    val model: String,
    val trim: String? = null,
    val engine: EngineSpec,
    val transmission: String? = null,
    val drivetrain: String? = null,
    val vin: String? = null,
    val odometerMiles: Int? = null,
    val nickname: String? = null,
    val identification: VehicleIdentification = VehicleIdentification(),
) {
    /** e.g. "2011 BMW 528i". */
    val displayName: String
        get() = listOfNotNull(year.toString(), make, model, trim?.takeIf { it.isNotBlank() })
            .joinToString(" ")

    /** Nickname if set, otherwise [displayName]. */
    val title: String
        get() = nickname?.takeIf { it.isNotBlank() } ?: displayName
}

/**
 * Engine details. [layout] is deliberately separate from [cylinders]: a six-cylinder engine
 * may be inline (e.g. BMW N52) or V-shaped, and neither FuelEconomy.gov nor every VIN decode
 * reports which. The layout is [EngineLayout.UNKNOWN] until a source or the user confirms it.
 */
data class EngineSpec(
    val displacementLiters: Double? = null,
    val cylinders: Int? = null,
    val layout: EngineLayout = EngineLayout.UNKNOWN,
    val fuelType: FuelType = FuelType.GASOLINE,
    val aspiration: Aspiration = Aspiration.NATURALLY_ASPIRATED,
    /** Manufacturer engine code such as "N52B30", if known. */
    val engineCode: String? = null,
    /** e.g. "Premium" when the source specifies a fuel grade. */
    val fuelGrade: String? = null,
) {
    /** e.g. "3.0L I6 Gasoline", or "3.0L 6-cyl Gasoline" when the layout is unknown. */
    val description: String
        get() {
            if (fuelType == FuelType.ELECTRIC) return FuelType.ELECTRIC.label
            val parts = mutableListOf<String>()
            displacementLiters?.let { parts += "%.1fL".format(java.util.Locale.US, it) }
            cylinders?.let { parts += layout.shortLabel(it) }
            if (aspiration != Aspiration.NATURALLY_ASPIRATED) parts += aspiration.label
            parts += fuelType.label
            return parts.joinToString(" ")
        }

    /** A sentence-friendly description used by Penelope, e.g. "3.0 liter inline six-cylinder". */
    val spokenDescription: String
        get() {
            if (fuelType == FuelType.ELECTRIC) return "electric drive"
            val parts = mutableListOf<String>()
            displacementLiters?.let { parts += "%.1f liter".format(java.util.Locale.US, it) }
            if (aspiration == Aspiration.NATURALLY_ASPIRATED && displacementLiters != null) {
                parts += "naturally aspirated"
            } else if (aspiration != Aspiration.NATURALLY_ASPIRATED) {
                parts += aspiration.spoken
            }
            cylinders?.let { parts += layout.spokenLabel(it) } ?: run { parts += "engine" }
            return parts.joinToString(" ")
        }
}

enum class EngineLayout(val label: String) {
    UNKNOWN("Not confirmed"),
    INLINE("Inline (straight)"),
    V("V"),
    FLAT("Flat / boxer"),
    W("W"),
    ROTARY("Rotary"),
    ;

    fun shortLabel(cylinders: Int): String = when (this) {
        INLINE -> "I$cylinders"
        V -> "V$cylinders"
        FLAT -> "H$cylinders"
        W -> "W$cylinders"
        ROTARY -> "rotary"
        UNKNOWN -> "$cylinders-cyl"
    }

    fun spokenLabel(cylinders: Int): String = when (this) {
        INLINE -> "inline ${cylinderWord(cylinders)}-cylinder"
        V -> "V${cylinders}"
        FLAT -> "flat ${cylinderWord(cylinders)}-cylinder"
        W -> "W${cylinders}"
        ROTARY -> "rotary engine"
        UNKNOWN -> "${cylinderWord(cylinders)}-cylinder"
    }

    private fun cylinderWord(n: Int) = when (n) {
        2 -> "two"; 3 -> "three"; 4 -> "four"; 5 -> "five"; 6 -> "six"
        8 -> "eight"; 10 -> "ten"; 12 -> "twelve"; else -> n.toString()
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

enum class Aspiration(val label: String, val spoken: String) {
    NATURALLY_ASPIRATED("Naturally aspirated", "naturally aspirated"),
    TURBOCHARGED("Turbo", "turbocharged"),
    SUPERCHARGED("Supercharged", "supercharged"),
}

/** How a profile's details were obtained, so later features know what has been verified. */
data class VehicleIdentification(
    val source: IdentificationSource = IdentificationSource.MANUAL,
    /** FuelEconomy.gov vehicle record id, when chosen from that catalog. */
    val catalogId: String? = null,
    /** True once the user has reviewed and confirmed the details. */
    val userConfirmed: Boolean = true,
)

enum class IdentificationSource(val label: String) {
    MANUAL("Entered manually"),
    CATALOG("Selected from FuelEconomy.gov"),
    VIN_DECODE("Decoded from VIN (NHTSA)"),
}
