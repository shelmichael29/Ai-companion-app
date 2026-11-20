package com.aicarcompanion.app.data.vehicledb

import com.aicarcompanion.app.domain.model.Aspiration
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.FuelType
import org.json.JSONException
import org.json.JSONObject

/** Local VIN checks performed before any network request. */
object VinValidator {
    private val ALLOWED = Regex("^[A-HJ-NPR-Z0-9]{17}$")
    private val WEIGHTS = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)

    sealed interface Result {
        /** [checkDigitValid] is false for many non-North-American VINs, which is not an error. */
        data class Valid(val vin: String, val checkDigitValid: Boolean) : Result
        data class Invalid(val reason: String) : Result
    }

    fun normalize(input: String): String = input.uppercase().filter { it.isLetterOrDigit() }

    fun validate(input: String): Result {
        val vin = normalize(input)
        if (vin.length != 17) return Result.Invalid("A VIN has 17 characters; this has ${vin.length}.")
        if (vin.any { it == 'I' || it == 'O' || it == 'Q' }) {
            return Result.Invalid("VINs never contain the letters I, O or Q. Check for 1 or 0.")
        }
        if (!ALLOWED.matches(vin)) return Result.Invalid("A VIN contains only letters and digits.")
        return Result.Valid(vin, checkDigit(vin) == vin[8])
    }

    fun checkDigit(vin: String): Char {
        val sum = vin.withIndex().sumOf { (i, c) -> transliterate(c) * WEIGHTS[i] }
        val r = sum % 11
        return if (r == 10) 'X' else ('0' + r)
    }

    private fun transliterate(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        'A', 'J' -> 1; 'B', 'K', 'S' -> 2; 'C', 'L', 'T' -> 3; 'D', 'M', 'U' -> 4
        'E', 'N', 'V' -> 5; 'F', 'W' -> 6; 'G', 'P', 'X' -> 7; 'H', 'Y' -> 8; 'R', 'Z' -> 9
        else -> 0
    }
}

/** Fields a VIN decoder could determine. Anything it couldn't is null / UNKNOWN. */
data class DecodedVehicle(
    val vin: String,
    val year: Int?,
    val make: String?,
    val model: String?,
    val trim: String?,
    val series: String?,
    val displacementLiters: Double?,
    val cylinders: Int?,
    val layout: EngineLayout,
    val engineCode: String?,
    val fuelType: FuelType?,
    val aspiration: Aspiration?,
    val transmission: String?,
    val drivetrain: String?,
    val bodyClass: String?,
    /** Who built it and where, as NHTSA reports it. Null when the VIN doesn't say. */
    val manufacturerName: String? = null,
    val plantCity: String? = null,
    val plantCountry: String? = null,
) {
    /** "Munich, Germany", or whichever part is known. */
    val builtAt: String? get() = listOfNotNull(plantCity, plantCountry).joinToString(", ").ifEmpty { null }

    /** Human-readable names of important fields the decoder could not determine. */
    val notDetermined: List<String>
        get() = buildList {
            if (trim == null) add("trim")
            if (layout == EngineLayout.UNKNOWN) add("engine layout (inline / V)")
            if (engineCode == null) add("engine code")
            if (transmission == null) add("transmission")
            if (drivetrain == null) add("drivetrain")
        }
}

sealed interface VinDecodeResult {
    data class Decoded(val vehicle: DecodedVehicle, val warnings: List<String>) : VinDecodeResult
    data class NotDecoded(val reason: String) : VinDecodeResult
    data class Failed(val error: LookupError) : VinDecodeResult
}

/** Decodes VINs. NHTSA vPIC is the first implementation. */
interface VinDecoder {
    val sourceName: String
    suspend fun decode(vin: String): VinDecodeResult
}

/**
 * Parses NHTSA vPIC `DecodeVinValues` responses. Verified behavior:
 * - Values are strings; missing data is "" or "Not Applicable".
 * - `ErrorCode` is a comma-separated list; "0" is a clean decode. Invalid VINs can still return
 *   misleading fields (e.g. a random manufacturer), so fatal codes discard the result.
 * - For many vehicles (e.g. 2011 BMW 528i) engine configuration, engine model, transmission
 *   and trim are blank.
 */
object VpicParser {
    /** 6 incomplete VIN, 7 manufacturer not registered, 8 no detailed data, 11 bad model year, 400 invalid chars. */
    private val FATAL_CODES = setOf("6", "7", "8", "11", "400")

    fun parse(vin: String, body: String): VinDecodeResult {
        val result = JSONObject(body).getJSONArray("Results").optJSONObject(0)
            ?: return VinDecodeResult.NotDecoded("No result returned for this VIN.")
        fun str(key: String) = result.optString(key).trim()
            .takeIf { it.isNotEmpty() && !it.equals("Not Applicable", true) && it != "null" }

        val codes = str("ErrorCode")?.split(",")?.map { it.trim() }.orEmpty().toSet()
        val errorText = str("ErrorText").orEmpty()
        val year = str("ModelYear")?.toIntOrNull()
        val make = str("Make")?.let(::tidyName)
        if (codes.any { it in FATAL_CODES } || year == null || make == null) {
            return VinDecodeResult.NotDecoded(
                errorText.ifEmpty { "This VIN could not be decoded." }.substringBefore(";").trim(),
            )
        }

        val warnings = buildList {
            if ("1" in codes) add("The VIN check digit doesn't match. Double-check the VIN (this is normal for some non-U.S. vehicles).")
            if ("3" in codes || "4" in codes) add("NHTSA assumed one character was mistyped. Verify the VIN carefully.")
            if ("5" in codes || "14" in codes) add("Some characters couldn't be decoded, so some details are missing.")
        }

        return VinDecodeResult.Decoded(
            DecodedVehicle(
                vin = vin,
                year = year,
                make = make,
                model = str("Model"),
                trim = str("Trim"),
                series = str("Series"),
                displacementLiters = str("DisplacementL")?.toDoubleOrNull()?.takeIf { it > 0 },
                cylinders = str("EngineCylinders")?.toIntOrNull()?.takeIf { it > 0 },
                layout = layoutOf(str("EngineConfiguration")),
                engineCode = str("EngineModel"),
                fuelType = fuelOf(str("FuelTypePrimary"), str("ElectrificationLevel")),
                aspiration = when {
                    str("Turbo").equals("Yes", true) -> Aspiration.TURBOCHARGED
                    else -> null // vPIC leaves this blank for most NA engines; don't assume.
                },
                transmission = listOfNotNull(
                    str("TransmissionSpeeds")?.let { "$it-speed" },
                    str("TransmissionStyle"),
                ).joinToString(" ").ifEmpty { null },
                drivetrain = str("DriveType"),
                bodyClass = str("BodyClass"),
                manufacturerName = str("Manufacturer")?.let(::tidyName),
                plantCity = str("PlantCity")?.let(::tidyName),
                plantCountry = str("PlantCountry")?.let(::tidyName),
            ),
            warnings,
        )
    }

    fun layoutOf(config: String?): EngineLayout = when {
        config == null -> EngineLayout.UNKNOWN
        config.contains("In-Line", true) || config.contains("Inline", true) -> EngineLayout.INLINE
        config.startsWith("V", true) -> EngineLayout.V
        config.contains("opposed", true) || config.contains("boxer", true) -> EngineLayout.FLAT
        config.startsWith("W", true) -> EngineLayout.W
        config.contains("Rotary", true) -> EngineLayout.ROTARY
        else -> EngineLayout.UNKNOWN
    }

    private fun fuelOf(primary: String?, electrification: String?): FuelType? = when {
        electrification?.contains("PHEV", true) == true -> FuelType.PLUG_IN_HYBRID
        electrification?.contains("HEV", true) == true -> FuelType.HYBRID
        electrification?.contains("BEV", true) == true || primary.equals("Electric", true) -> FuelType.ELECTRIC
        primary?.contains("Diesel", true) == true -> FuelType.DIESEL
        primary?.contains("Gasoline", true) == true -> FuelType.GASOLINE
        primary?.contains("Flex", true) == true -> FuelType.FLEX_FUEL
        else -> null
    }

    /** "HONDA" -> "Honda", "MERCEDES-BENZ" -> "Mercedes-Benz"; short acronyms like "BMW" stay. */
    fun tidyName(name: String): String {
        if (name.length <= 3 || name != name.uppercase()) return name
        return name.lowercase().split(" ").joinToString(" ") { word ->
            word.split("-").joinToString("-") { it.replaceFirstChar(Char::uppercase) }
        }
    }
}

class NhtsaVinDecoder(private val http: HttpClient) : VinDecoder {
    override val sourceName = "NHTSA vPIC"

    override suspend fun decode(vin: String): VinDecodeResult {
        val valid = when (val v = VinValidator.validate(vin)) {
            is VinValidator.Result.Invalid -> return VinDecodeResult.NotDecoded(v.reason)
            is VinValidator.Result.Valid -> v.vin
        }
        val response = try {
            http.get("$BASE/DecodeVinValues/$valid?format=json", emptyMap())
        } catch (e: java.io.IOException) {
            return VinDecodeResult.Failed(LookupError.OFFLINE)
        }
        if (response.code !in 200..299) return VinDecodeResult.Failed(LookupError.SERVICE)
        return try {
            VpicParser.parse(valid, response.body)
        } catch (e: JSONException) {
            VinDecodeResult.Failed(LookupError.MALFORMED)
        }
    }

    companion object {
        const val BASE = "https://vpic.nhtsa.dot.gov/api/vehicles"
    }
}
