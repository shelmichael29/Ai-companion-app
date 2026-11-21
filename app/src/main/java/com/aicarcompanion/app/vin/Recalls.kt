package com.aicarcompanion.app.vin

import com.aicarcompanion.app.connectivity.ConnectionLog
import com.aicarcompanion.app.connectivity.LogArea
import com.aicarcompanion.app.data.vehicledb.HttpClient
import com.aicarcompanion.app.data.vehicledb.LookupError
import com.aicarcompanion.app.data.vehicledb.urlEncoded
import com.aicarcompanion.app.diagnostics.knowledge.OfflineKnowledge
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Recall campaigns for a vehicle's year, make and model, from NHTSA.
 *
 * What this can and can't say (the rule behind every message in this file):
 * NHTSA's public search by year/make/model lists recall CAMPAIGNS for that model. It does NOT say
 * whether a specific VIN is affected, nor whether its repair was done. Only a VIN-specific source
 * (nhtsa.gov/recalls with the VIN, or the manufacturer) can. Nothing here is ever labeled as
 * confirmed for the user's car.
 */

data class RecallCampaignInfo(
    val campaignNumber: String,
    val manufacturer: String?,
    /** Raw NHTSA component, e.g. "AIR BAGS:FRONTAL:SENSOR/CONTROL MODULE-INACTIVE". */
    val component: String?,
    val summary: String?,
    val consequence: String?,
    val remedy: String?,
    /** When the manufacturer's report was received by NHTSA (not a repair or completion date). */
    val reportedToNhtsa: LocalDate?,
    /** NHTSA flags that some affected vehicles shouldn't be driven. */
    val doNotDrive: Boolean,
    val parkOutside: Boolean,
    val overTheAirUpdate: Boolean,
)

/** Where a result came from. A saved snapshot is shown only when NHTSA couldn't be reached. */
sealed interface RecallSource {
    data object NhtsaLive : RecallSource
    data class SavedSnapshot(val asOf: String?) : RecallSource
}

sealed interface RecallLookupResult {
    data class Found(val campaigns: List<RecallCampaignInfo>, val source: RecallSource) : RecallLookupResult
    /** Nothing was returned for the model. This is not proof the car has no recalls. */
    data class NoneFound(val source: RecallSource) : RecallLookupResult
    data class Unavailable(val error: LookupError) : RecallLookupResult
}

interface RecallLookup {
    suspend fun forVehicle(year: Int, make: String, model: String): RecallLookupResult
}

/** Parses `api.nhtsa.gov/recalls/recallsByVehicle` (verified 2026-11-20: dates are DD/MM/YYYY). */
object NhtsaRecallParser {
    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)

    fun parse(body: String): List<RecallCampaignInfo> {
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        val seen = mutableSetOf<String>()
        return (0 until results.length()).mapNotNull { results.optJSONObject(it) }.mapNotNull { r ->
            fun text(key: String) = r.optString(key).replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() && it != "null" }
            val number = text("NHTSACampaignNumber") ?: return@mapNotNull null
            if (!seen.add(number)) return@mapNotNull null
            RecallCampaignInfo(
                campaignNumber = number,
                manufacturer = text("Manufacturer"),
                component = text("Component"),
                summary = text("Summary"),
                consequence = text("Consequence"),
                remedy = text("Remedy"),
                reportedToNhtsa = text("ReportReceivedDate")?.let { runCatching { LocalDate.parse(it, DATE) }.getOrNull() },
                doNotDrive = r.optBoolean("parkIt"),
                parkOutside = r.optBoolean("parkOutSide"),
                overTheAirUpdate = r.optBoolean("overTheAirUpdate"),
            )
        }.sortedWith(compareByDescending<RecallCampaignInfo> { it.reportedToNhtsa }.thenBy { it.campaignNumber })
    }
}

/** The official NHTSA recalls API. Only year, make and model are sent: never the VIN. */
class NhtsaRecallLookup(private val http: HttpClient, private val log: ConnectionLog? = null) : RecallLookup {
    override suspend fun forVehicle(year: Int, make: String, model: String): RecallLookupResult {
        val url = "$BASE?make=${make.trim().urlEncoded()}&model=${model.trim().urlEncoded()}&modelYear=$year"
        val result = try {
            val response = http.get(url, mapOf("Accept" to "application/json"))
            when {
                response.code !in 200..299 -> RecallLookupResult.Unavailable(LookupError.SERVICE)
                else -> {
                    val campaigns = NhtsaRecallParser.parse(response.body)
                    if (campaigns.isEmpty()) RecallLookupResult.NoneFound(RecallSource.NhtsaLive)
                    else RecallLookupResult.Found(campaigns, RecallSource.NhtsaLive)
                }
            }
        } catch (e: java.io.IOException) {
            RecallLookupResult.Unavailable(LookupError.OFFLINE)
        } catch (e: JSONException) {
            RecallLookupResult.Unavailable(LookupError.MALFORMED)
        }
        // Categories and counts only. No VIN, make or model.
        log?.record(
            LogArea.APP, "Recall lookup",
            mapOf(
                "result" to when (result) {
                    is RecallLookupResult.Found -> "found"
                    is RecallLookupResult.NoneFound -> "none"
                    is RecallLookupResult.Unavailable -> result.error.name.lowercase()
                },
                "count" to (result as? RecallLookupResult.Found)?.campaigns?.size,
            ),
        )
        return result
    }

    companion object {
        const val BASE = "https://api.nhtsa.gov/recalls/recallsByVehicle"
    }
}

/**
 * Live NHTSA first. If it can't be reached, the offline knowledge pack's saved snapshot (campaigns
 * for the model, as of its download date) is shown and labeled as such.
 */
class FallbackRecallLookup(private val live: RecallLookup, private val offline: OfflineKnowledge) : RecallLookup {
    override suspend fun forVehicle(year: Int, make: String, model: String): RecallLookupResult {
        val result = live.forVehicle(year, make, model)
        if (result !is RecallLookupResult.Unavailable || !offline.available) return result
        val saved = offline.recalls(year, make, model, limit = 50)
        // Zero saved campaigns may only mean the model name didn't match, so it isn't shown as "none".
        if (saved.isEmpty()) return result
        val snapshot = RecallSource.SavedSnapshot(offline.info?.recallSnapshot)
        return RecallLookupResult.Found(
            saved.map {
                RecallCampaignInfo(
                    campaignNumber = it.campaign, manufacturer = null, component = it.component, summary = it.summary,
                    consequence = it.consequence, remedy = it.remedy,
                    reportedToNhtsa = it.reportDate?.let { d -> runCatching { LocalDate.parse(d, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() },
                    doNotDrive = false, parkOutside = false, overTheAirUpdate = false,
                )
            },
            snapshot,
        )
    }
}

/** How a recall result is worded. Never claims anything about the user's specific car. */
data class RecallStatus(val headline: String, val body: String, val urgent: Boolean = false, val showVerifyLink: Boolean = true)

object RecallMessaging {
    const val VERIFY_HINT = "To find out whether any apply to your car, and whether they've been repaired, check your VIN with NHTSA."

    fun status(result: RecallLookupResult, vehicleName: String): RecallStatus = when (result) {
        is RecallLookupResult.Found -> {
            val n = result.campaigns.size
            val noun = if (n == 1) "recall campaign" else "recall campaigns"
            val urgent = result.campaigns.any { it.doNotDrive }
            RecallStatus(
                headline = "$n $noun found for the $vehicleName",
                body = buildString {
                    append("These recalls apply to this year, make and model. NHTSA's public search can't tell me whether your specific car is included or whether it has been repaired. ")
                    append(VERIFY_HINT)
                    if (urgent) append(" For at least one campaign, the manufacturer advises that some affected vehicles shouldn't be driven, so please check your VIN as soon as you can.")
                    savedNote(result.source)?.let { append(" $it") }
                },
                urgent = urgent,
            )
        }
        is RecallLookupResult.NoneFound -> RecallStatus(
            headline = "No recall campaigns were returned for the $vehicleName",
            body = "That doesn't guarantee your car has no recalls: new ones are added over time, and model names don't always match. $VERIFY_HINT",
        )
        is RecallLookupResult.Unavailable -> RecallStatus(
            headline = "I couldn't check recalls right now",
            body = "You can still save your car. Check recalls any time with your VIN at nhtsa.gov/recalls.",
        )
    }

    private fun savedNote(source: RecallSource): String? = (source as? RecallSource.SavedSnapshot)?.let {
        "I couldn't reach NHTSA, so this is saved data" + (it.asOf?.let { d -> " from $d" } ?: "") + ", which may be out of date."
    }

    /** "AIR BAGS:FRONTAL:SENSOR/CONTROL MODULE-INACTIVE" → "Air bags › Frontal › Sensor/control module-inactive". */
    fun friendlyComponent(raw: String?): String? = raw?.split(':')?.map { it.trim().lowercase(Locale.US) }?.filter { it.isNotEmpty() }
        ?.joinToString(" › ") { it.replaceFirstChar(Char::uppercase) }?.takeIf { it.isNotEmpty() }

    fun dateLabel(date: LocalDate?): String? = date?.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
}

/**
 * Where to check a VIN for real. NHTSA's lookup is VIN-specific for every manufacturer; the
 * manufacturer pages below are the ones confirmed reachable on 2026-11-20. Links only ever contain
 * a fixed address, never the VIN.
 */
object RecallVerificationLinks {
    const val NHTSA_VIN_LOOKUP = "https://www.nhtsa.gov/recalls"

    private val MANUFACTURER = mapOf(
        "TOYOTA" to "https://www.toyota.com/recall",
        "LEXUS" to "https://www.toyota.com/recall",
        "VOLKSWAGEN" to "https://www.vw.com/en/owners-and-services/about-my-vehicle/recalls.html",
        "MAZDA" to "https://www.mazdausa.com/owners/recalls",
    )

    data class Link(val label: String, val url: String)

    fun forMake(make: String?): List<Link> = buildList {
        add(Link("Check my VIN on NHTSA.gov (all makes)", NHTSA_VIN_LOOKUP))
        MANUFACTURER[make?.trim()?.uppercase(Locale.US)]?.let { add(Link("Check my VIN with ${make!!.trim()}", it)) }
    }
}
