package com.aicarcompanion.app.data.vehicledb

import com.aicarcompanion.app.domain.model.Aspiration
import com.aicarcompanion.app.domain.model.FuelType
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Parses FuelEconomy.gov web-service JSON (https://www.fueleconomy.gov/feg/ws/).
 * Verified response quirks:
 * - `menuItem` is an array, but a single object when there is exactly one result.
 * - A query with no results returns the literal body `null`.
 * - Option text looks like "Auto (S8), 6 cyl, 3.0 L" or "Man 6-spd, 4 cyl, 2.0 L, Diesel, Turbo";
 *   electric vehicles have only a transmission ("Auto (A1)").
 * - Engine layout (inline vs V) is never provided.
 */
object FuelEconomyParser {

    data class MenuItem(val text: String, val value: String)

    fun parseMenu(body: String): List<MenuItem> {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || trimmed == "null") return emptyList()
        val root = JSONObject(trimmed)
        val items = when (val node = root.opt("menuItem")) {
            is JSONArray -> (0 until node.length()).map { node.getJSONObject(it) }
            is JSONObject -> listOf(node)
            else -> emptyList()
        }
        return items.map { MenuItem(it.optString("text"), it.optString("value")) }
    }

    private val CYL = Regex("""^(\d+)\s*cyl$""", RegexOption.IGNORE_CASE)
    private val DISPL = Regex("""^(\d+(?:\.\d+)?)\s*L$""", RegexOption.IGNORE_CASE)

    fun parseConfiguration(item: MenuItem): VehicleConfiguration {
        val parts = item.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        var cylinders: Int? = null
        var displacement: Double? = null
        val extras = mutableListOf<String>()
        parts.drop(1).forEach { part ->
            CYL.find(part)?.let { cylinders = it.groupValues[1].toInt(); return@forEach }
            DISPL.find(part)?.let { displacement = it.groupValues[1].toDouble(); return@forEach }
            extras += part
        }
        return VehicleConfiguration(
            id = item.value,
            rawText = item.text,
            transmission = parts.firstOrNull().orEmpty(),
            cylinders = cylinders,
            displacementLiters = displacement,
            extras = extras,
        )
    }

    fun parseSpecs(body: String): CatalogVehicleSpecs {
        val o = JSONObject(body)
        fun str(key: String) = o.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
        val fuel1 = str("fuelType1")
        val atv = str("atvType")
        return CatalogVehicleSpecs(
            id = str("id") ?: "",
            year = str("year")?.toIntOrNull(),
            make = str("make"),
            model = str("model"),
            baseModel = str("baseModel"),
            displacementLiters = str("displ")?.toDoubleOrNull()?.takeIf { it > 0 },
            cylinders = str("cylinders")?.toIntOrNull()?.takeIf { it > 0 },
            transmission = str("trany"),
            drivetrain = str("drive"),
            fuelType = fuelTypeOf(fuel1, atv),
            fuelGrade = when {
                fuel1?.startsWith("Premium", ignoreCase = true) == true -> "Premium"
                fuel1?.startsWith("Regular", ignoreCase = true) == true -> "Regular"
                fuel1?.startsWith("Midgrade", ignoreCase = true) == true -> "Midgrade"
                else -> null
            },
            aspiration = when {
                str("tCharger") != null -> Aspiration.TURBOCHARGED
                str("sCharger") != null -> Aspiration.SUPERCHARGED
                else -> Aspiration.NATURALLY_ASPIRATED
            },
            vehicleClass = str("VClass"),
        )
    }

    private fun fuelTypeOf(fuel1: String?, atv: String?): FuelType? = when {
        atv.equals("EV", true) || fuel1.equals("Electricity", true) -> FuelType.ELECTRIC
        atv.equals("Plug-in Hybrid", true) -> FuelType.PLUG_IN_HYBRID
        atv.equals("Hybrid", true) -> FuelType.HYBRID
        atv.equals("FFV", true) -> FuelType.FLEX_FUEL
        atv.equals("Diesel", true) || fuel1?.contains("Diesel", true) == true -> FuelType.DIESEL
        fuel1?.contains("Gasoline", true) == true -> FuelType.GASOLINE
        else -> null
    }
}

class FuelEconomyCatalogProvider(
    http: HttpClient,
    cache: ResponseCache,
    now: () -> Long = System::currentTimeMillis,
) : VehicleCatalogProvider {

    override val sourceName = "FuelEconomy.gov (U.S. DOE/EPA)"

    private val fetcher = CachingFetcher(http, cache, maxAgeMillis = 30L * 24 * 60 * 60 * 1000, now)
    private val headers = mapOf("Accept" to "application/json")

    override suspend fun years() =
        menu("$BASE/vehicle/menu/year").map { items -> items.mapNotNull { it.value.toIntOrNull() }.sortedDescending() }

    override suspend fun makes(year: Int) =
        menu("$BASE/vehicle/menu/make?year=$year").map { items -> items.map { it.value } }

    override suspend fun models(year: Int, make: String) =
        menu("$BASE/vehicle/menu/model?year=$year&make=${make.urlEncoded()}").map { items -> items.map { it.value } }

    override suspend fun configurations(year: Int, make: String, model: String) =
        menu("$BASE/vehicle/menu/options?year=$year&make=${make.urlEncoded()}&model=${model.urlEncoded()}")
            .map { items -> items.map(FuelEconomyParser::parseConfiguration) }

    override suspend fun specs(configurationId: String): LookupResult<CatalogVehicleSpecs> =
        parsing(fetcher.fetch("$BASE/vehicle/${configurationId.urlEncoded()}", headers), FuelEconomyParser::parseSpecs)

    private suspend fun menu(url: String) = parsing(fetcher.fetch(url, headers), FuelEconomyParser::parseMenu)

    private fun <T> parsing(result: LookupResult<String>, parse: (String) -> T): LookupResult<T> = when (result) {
        is LookupResult.Failure -> result
        is LookupResult.Success -> try {
            LookupResult.Success(parse(result.value), result.fromCache)
        } catch (e: JSONException) {
            LookupResult.Failure(LookupError.MALFORMED)
        }
    }

    companion object {
        const val BASE = "https://www.fueleconomy.gov/ws/rest"
    }
}
