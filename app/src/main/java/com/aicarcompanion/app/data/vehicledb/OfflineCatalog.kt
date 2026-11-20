package com.aicarcompanion.app.data.vehicledb

import com.aicarcompanion.app.data.knowledge.SqliteOfflineKnowledge
import org.json.JSONObject

/**
 * The EPA vehicle list from the offline knowledge pack (model years 1996+), presented exactly like
 * the FuelEconomy.gov web service: same IDs and the same parser, so a car picked offline matches
 * one picked online.
 */
class OfflineEpaCatalog(private val knowledge: SqliteOfflineKnowledge) : VehicleCatalogProvider {
    override val sourceName = "FuelEconomy.gov (U.S. DOE/EPA), offline copy"

    private fun <T> offline(value: T, empty: Boolean): LookupResult<T> =
        if (!knowledge.available || empty) LookupResult.Failure(LookupError.OFFLINE) else LookupResult.Success(value, fromCache = true)

    override suspend fun years() = knowledge.epaYears().let { offline(it, it.isEmpty()) }
    override suspend fun makes(year: Int) = knowledge.epaMakes(year).let { offline(it, it.isEmpty()) }
    override suspend fun models(year: Int, make: String) = knowledge.epaModels(year, make).let { offline(it, it.isEmpty()) }

    override suspend fun configurations(year: Int, make: String, model: String): LookupResult<List<VehicleConfiguration>> {
        val rows = knowledge.epaRows(year, make, model)
        return offline(rows.map { r -> FuelEconomyParser.parseConfiguration(FuelEconomyParser.MenuItem(menuText(r), r["id"].orEmpty())) }, rows.isEmpty())
    }

    override suspend fun specs(configurationId: String): LookupResult<CatalogVehicleSpecs> {
        val r = knowledge.epaRow(configurationId) ?: return LookupResult.Failure(LookupError.OFFLINE)
        return LookupResult.Success(FuelEconomyParser.parseSpecs(JSONObject(r.filterValues { it != null }).toString()), fromCache = true)
    }

    companion object {
        /** The web service's option text, e.g. "Auto (S8), 6 cyl, 3.0 L, Turbo". */
        fun menuText(r: Map<String, String?>): String = listOfNotNull(
            r["trany"],
            r["cylinders"]?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }?.let { "$it cyl" },
            r["displ"]?.toDoubleOrNull()?.takeIf { it > 0 }?.let { "$it L" },
            "Turbo".takeIf { r["tCharger"] != null },
            "Supercharged".takeIf { r["sCharger"] != null },
            r["atvType"]?.takeIf { it.isNotBlank() },
        ).joinToString(", ")
    }
}

/**
 * Online first (always the newest data); when the web service can't be reached, the offline copy
 * answers instead, so adding a car works without internet.
 */
class FallbackVehicleCatalog(private val online: VehicleCatalogProvider, private val offline: VehicleCatalogProvider) : VehicleCatalogProvider {
    override val sourceName: String get() = online.sourceName

    private suspend fun <T> either(primary: suspend () -> LookupResult<T>, backup: suspend () -> LookupResult<T>): LookupResult<T> {
        val first = primary()
        if (first is LookupResult.Success) return first
        val second = backup()
        return if (second is LookupResult.Success) second else first
    }

    override suspend fun years() = either({ online.years() }, { offline.years() })
    override suspend fun makes(year: Int) = either({ online.makes(year) }, { offline.makes(year) })
    override suspend fun models(year: Int, make: String) = either({ online.models(year, make) }, { offline.models(year, make) })
    override suspend fun configurations(year: Int, make: String, model: String) =
        either({ online.configurations(year, make, model) }, { offline.configurations(year, make, model) })
    override suspend fun specs(configurationId: String) = either({ online.specs(configurationId) }, { offline.specs(configurationId) })
}
