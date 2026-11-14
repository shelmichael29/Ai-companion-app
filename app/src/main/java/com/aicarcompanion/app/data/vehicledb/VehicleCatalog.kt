package com.aicarcompanion.app.data.vehicledb

import com.aicarcompanion.app.domain.model.Aspiration
import com.aicarcompanion.app.domain.model.FuelType

/**
 * A source of year → make → model → configuration data. FuelEconomy.gov is the first
 * implementation; a proprietary backend or offline knowledge pack can implement the same API.
 */
interface VehicleCatalogProvider {
    val sourceName: String

    suspend fun years(): LookupResult<List<Int>>
    suspend fun makes(year: Int): LookupResult<List<String>>
    suspend fun models(year: Int, make: String): LookupResult<List<String>>
    suspend fun configurations(year: Int, make: String, model: String): LookupResult<List<VehicleConfiguration>>
    suspend fun specs(configurationId: String): LookupResult<CatalogVehicleSpecs>
}

/** One engine/transmission combination offered for a year/make/model. */
data class VehicleConfiguration(
    val id: String,
    val rawText: String,
    val transmission: String,
    val cylinders: Int?,
    val displacementLiters: Double?,
    /** Extra tokens from the source such as "Turbo", "Diesel", "FFV", "Hybrid". */
    val extras: List<String>,
) {
    /** Groups configurations that share an engine, e.g. "3.0L 6-cyl" or "2.0L 4-cyl Turbo Diesel". */
    val engineLabel: String
        get() {
            if (cylinders == null && displacementLiters == null) {
                return (listOf("No engine listed (likely electric)") + extras).joinToString(" ")
            }
            return listOfNotNull(
                displacementLiters?.let { "%.1fL".format(java.util.Locale.US, it) },
                cylinders?.let { "$it-cyl" },
            ).plus(extras).joinToString(" ")
        }

    val transmissionLabel: String get() = TransmissionText.describe(transmission)
}

/** Details for one catalog record. Fields are null when the source doesn't provide them. */
data class CatalogVehicleSpecs(
    val id: String,
    val year: Int?,
    val make: String?,
    val model: String?,
    val baseModel: String?,
    val displacementLiters: Double?,
    val cylinders: Int?,
    val transmission: String?,
    val drivetrain: String?,
    val fuelType: FuelType?,
    val fuelGrade: String?,
    val aspiration: Aspiration?,
    val vehicleClass: String?,
)

/** Turns FuelEconomy-style transmission codes into readable text. */
object TransmissionText {
    private val SPEEDS = Regex("""(\d+)""")

    fun describe(raw: String): String {
        val t = raw.trim()
        val speeds = SPEEDS.find(t)?.groupValues?.get(1)
        return when {
            t.startsWith("Man", ignoreCase = true) ->
                listOfNotNull("Manual", speeds?.let { "$it-speed" }).joinToString(", ")
            t.contains("variable gear", ignoreCase = true) || t.contains("AV", ignoreCase = false) && !t.contains("AV-S") ->
                "Automatic (CVT)"
            t.contains("AV-S") -> "Automatic (CVT${speeds?.let { " with $it simulated steps" } ?: ""})"
            t.contains("AM") -> listOfNotNull("Automated manual", speeds?.let { "$it-speed" }).joinToString(", ")
            t.contains("A1)") -> "Automatic, single-speed"
            t.startsWith("Auto", ignoreCase = true) -> listOfNotNull(
                "Automatic",
                speeds?.let { "$it-speed" },
                if (Regex("""\(S\d""").containsMatchIn(t)) "with manual shift mode" else null,
            ).joinToString(", ")
            else -> t
        }
    }
}
