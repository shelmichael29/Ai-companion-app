package com.aicarcompanion.app.vin

import com.aicarcompanion.app.connectivity.ConnectionLog
import com.aicarcompanion.app.connectivity.LogArea
import com.aicarcompanion.app.data.vehicledb.HttpClient
import com.aicarcompanion.app.data.vehicledb.LookupError
import com.aicarcompanion.app.data.vehicledb.urlEncoded
import com.aicarcompanion.app.diagnostics.knowledge.OfflineKnowledge
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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

fun interface RecallLookup {
    suspend fun forVehicle(year: Int, make: String, model: String): RecallLookupResult
}

/** Parses `api.nhtsa.gov/recalls/recallsByVehicle` (verified 2026-11-20: dates are DD/MM/YYYY). */
object NhtsaRecallParser {
    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)

    /**
     * @throws JSONException when [body] isn't a recall response. A reply without a `results` list, or
     * whose records are all unusable, is a failure: it must never read as "no campaigns".
     */
    fun parse(body: String): List<RecallCampaignInfo> {
        val root = JSONObject(body)
        val results = root.optJSONArray("results") ?: throw JSONException("no results list")
        val expected = root.optInt("Count", -1)
        if (expected > 0 && results.length() == 0) throw JSONException("count without results")
        val campaigns = parseRecords(results)
        if (results.length() > 0 && campaigns.isEmpty()) throw JSONException("no usable campaigns")
        return campaigns
    }

    private fun parseRecords(results: org.json.JSONArray): List<RecallCampaignInfo> {
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            RecallLookupResult.Unavailable(LookupError.SERVICE) // never an empty list for an unexpected failure
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
        // The local database query runs off the main thread, and isn't cut short: a count must be the real count.
        val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { offline.recalls(year, make, model, limit = 500) }
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
    private const val VERIFY_ALOUD = "To find out whether any apply to your car, and whether they've been repaired, check your VIN at nhtsa.gov/recalls."

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
            body = "That doesn't mean there are none: the check just didn't finish. You can still save your car. " +
                "Check recalls any time with your VIN at nhtsa.gov/recalls.",
        )
    }

    /**
     * Repair eligibility is never worked out here: this app has no mileage, age or date rule for it, and
     * must not invent one. Only explains what it can depend on, and who can say.
     */
    const val ELIGIBILITY_NOTE =
        "Whether a recall can be repaired at no cost can depend on your car's age, when it was first sold at retail, " +
            "when the campaign was announced, and the manufacturer's policies. I can't tell from here whether a recall " +
            "applies to your car or whether it's eligible. A dealer or the manufacturer can tell you."

    /** One line for voice: the same message, shorter. */
    private const val ELIGIBILITY_SPOKEN =
        "Whether a repair is free can depend on your car's age, when it was first sold, and the manufacturer's policies, so ask a dealer."

    /** "Nov 21, 2026, 2:15 PM" in the phone's language and time zone. */
    fun checkedLabel(at: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone).format(at)

    /** Where the model-level list came from and when it was checked. Shown with every result and every failure. */
    fun sourceLine(report: RecallReport, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val at = checkedLabel(report.checkedAt, zone, locale)
        val source = when (val r = report.result) {
            is RecallLookupResult.Found -> r.source
            is RecallLookupResult.NoneFound -> r.source
            is RecallLookupResult.Unavailable -> return "Source: the NHTSA recalls API, which couldn't be reached. Tried $at."
        }
        return when (source) {
            RecallSource.NhtsaLive -> "Source: NHTSA recalls API (live). Checked $at."
            is RecallSource.SavedSnapshot ->
                "Source: saved NHTSA data" + (source.asOf?.let { " from $it" }.orEmpty()) +
                    ". The live lookup failed at $at, so this may be out of date."
        }
    }

    /** The VIN-specific part, kept apart from the model-level list. Only a verified source can say "open for your VIN". */
    data class VinSection(val headline: String, val body: String, val verified: Boolean)

    fun vinSection(status: VinRecallStatus, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): VinSection = when (status) {
        VinRecallStatus.NotChecked -> VinSection(
            "Not confirmed for your VIN",
            "NHTSA's public data lists campaigns by year, make and model only. I can't tell whether your specific car has an " +
                "open recall or whether one was already repaired. Check your VIN with NHTSA to find out.",
            verified = false,
        )
        is VinRecallStatus.Unavailable -> VinSection(
            "I couldn't check your VIN",
            "That doesn't mean there are no open recalls: the check didn't finish. Check your VIN with NHTSA.",
            verified = false,
        )
        is VinRecallStatus.Verified -> {
            val at = checkedLabel(status.checkedAt, zone, locale)
            val n = status.open.size
            VinSection(
                if (n == 0) "No open recalls reported for your VIN" else "$n open ${if (n == 1) "recall" else "recalls"} reported for your VIN",
                "Reported by ${status.source}, checked $at. New recalls are added over time, so check again before you rely on this.",
                verified = true,
            )
        }
    }

    /**
     * Penelope's spoken and written answer to "any recalls on my car?". Same data and same caveats as the
     * screens. Never says there are none when the check failed.
     */
    fun assistantAnswer(report: RecallReport): String {
        val name = report.target.displayName
        return when (val r = report.result) {
            is RecallLookupResult.Unavailable ->
                "I couldn't check recalls for the $name just now, so I can't tell you whether there are any. " +
                    "Try again in a little while, or check your VIN at nhtsa.gov/recalls."
            is RecallLookupResult.NoneFound ->
                "NHTSA's recall search returned no recall campaigns for the $name${sourceClause(r.source)}. That doesn't guarantee your car " +
                    "has no recalls: new ones are added over time and model names don't always match. $VERIFY_ALOUD $ELIGIBILITY_SPOKEN"
            is RecallLookupResult.Found -> {
                val n = r.campaigns.size
                val examples = r.campaigns.take(3).joinToString("; ") { c ->
                    listOfNotNull(
                        friendlyComponent(c.component),
                        c.consequence?.let { "risk: ${it.trimEnd('.')}" },
                    ).joinToString(", ").ifEmpty { "recall" } + " (campaign ${c.campaignNumber})"
                }
                val urgent = if (r.campaigns.any { it.doNotDrive }) {
                    " For at least one, the manufacturer advises that some affected vehicles shouldn't be driven, so please check your VIN soon."
                } else ""
                "NHTSA lists $n ${if (n == 1) "recall campaign" else "recall campaigns"} for the $name${sourceClause(r.source)}. " +
                    "For example: $examples. These are for the model, not confirmed for your car.$urgent $VERIFY_ALOUD $ELIGIBILITY_SPOKEN"
            }
        }
    }

    /** The written answer: the spoken one plus where it came from and when, so the transcript shows it. */
    fun assistantAnswerText(report: RecallReport, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        assistantAnswer(report) + " " + sourceLine(report, zone, locale)

    private fun sourceClause(source: RecallSource): String = when (source) {
        RecallSource.NhtsaLive -> ", checked just now"
        is RecallSource.SavedSnapshot ->
            ", from saved NHTSA data" + (source.asOf?.let { " dated $it" }.orEmpty()) + " because I couldn't reach NHTSA just now, so it may be out of date"
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
