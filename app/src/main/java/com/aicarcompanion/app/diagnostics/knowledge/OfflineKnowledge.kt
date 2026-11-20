package com.aicarcompanion.app.diagnostics.knowledge

import com.aicarcompanion.app.diagnostics.dtc.DtcInfo
import com.aicarcompanion.app.diagnostics.dtc.DtcKnowledgeSource
import com.aicarcompanion.app.domain.model.DtcSeverity

/*
 * Penelope's offline knowledge pack (docs/OFFLINE_KNOWLEDGE.md): community trouble-code data,
 * EPA vehicle data, NHTSA recall campaigns and crash ratings, and symptom triage guides, all
 * read-only and versioned. Platform-neutral (no android.*), so an iOS app can ship the same pack.
 *
 * Rules:
 * - A trouble code is a clue, not a diagnosis; causes are "common causes".
 * - Recalls here are campaigns for a model, never proof a specific car is affected; the VIN check
 *   is online at nhtsa.gov/recalls.
 * - Safety answers never depend on this data (they stay built-in).
 */

data class KnowledgeCause(val likelihood: String?, val label: String)

data class KnowledgeDtc(
    val code: String,
    val title: String,
    val description: String,
    val causes: List<KnowledgeCause>,
    val symptoms: List<String>,
    val turnsOnCheckEngineLight: Boolean,
    val limpModePossible: Boolean,
    val diyPossible: Boolean?,
    val reviewed: String?,
    val category: String?,
)

data class ManufacturerDtc(val code: String, val make: String, val description: String)

data class TriageGuide(
    val id: String,
    val symptom: String,
    val questions: List<String>,
    val systems: List<String>,
    val evidence: List<String>,
    val warning: String?,
)

data class RecallCampaign(
    val campaign: String,
    val component: String?,
    val reportDate: String?,
    val summary: String?,
    val consequence: String?,
    val remedy: String?,
)

data class SafetyRating(val model: String, val body: String?, val drive: String?, val overall: Int?, val frontal: Int?, val side: Int?, val rollover: Int?)

/** Where the pack's data came from, for the status screen and answers. */
data class KnowledgeInfo(val builtUtc: String, val recallSnapshot: String?)

interface OfflineKnowledge {
    /** False until the pack is installed (first launch) or if it's missing; callers then use built-in data. */
    val available: Boolean
    val info: KnowledgeInfo?
    fun dtc(code: String): KnowledgeDtc?
    fun manufacturerDtc(code: String, make: String): ManufacturerDtc?
    fun triageGuides(): List<TriageGuide>
    fun recalls(year: Int, make: String, model: String, limit: Int = 50): List<RecallCampaign>
    fun recallCount(year: Int, make: String, model: String): Int
    fun safetyRatings(year: Int, make: String, model: String): List<SafetyRating>
}

object NoOfflineKnowledge : OfflineKnowledge {
    override val available = false
    override val info: KnowledgeInfo? = null
    override fun dtc(code: String): KnowledgeDtc? = null
    override fun manufacturerDtc(code: String, make: String): ManufacturerDtc? = null
    override fun triageGuides(): List<TriageGuide> = emptyList()
    override fun recalls(year: Int, make: String, model: String, limit: Int): List<RecallCampaign> = emptyList()
    override fun recallCount(year: Int, make: String, model: String): Int = 0
    override fun safetyRatings(year: Int, make: String, model: String): List<SafetyRating> = emptyList()
}

/**
 * Trouble-code meanings: Penelope's hand-written explanations first (written for beginners),
 * then the selected car's manufacturer-specific meaning, then the generic community catalog.
 */
class KnowledgeDtcSource(
    private val curated: DtcKnowledgeSource,
    private val knowledge: OfflineKnowledge,
    /** The selected car's make, for manufacturer-specific codes (P1xxx etc.). */
    private val make: () -> String? = { null },
) : DtcKnowledgeSource {

    override fun lookup(code: String): DtcInfo? {
        val c = code.trim().uppercase()
        curated.lookup(c)?.let { return it }
        if (!knowledge.available) return null
        if (isManufacturerRange(c)) {
            // Its meaning depends on the make: only the selected car's make is used, never a guess.
            val m = make()?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
            return knowledge.manufacturerDtc(c, m)?.let(::fromManufacturer)
        }
        return knowledge.dtc(c)?.let(::fromGeneric)
    }

    companion object {
        /** Codes whose meaning is set by each manufacturer (SAE J2012 ranges). */
        fun isManufacturerRange(code: String): Boolean = code.length == 5 && when (code[0]) {
            // P1xxx and P30xx–P33xx are manufacturer-controlled; P34xx–P39xx are generic.
            'P' -> code[1] == '1' || (code[1] == '3' && code[2] in '0'..'3')
            'B', 'C', 'U' -> code[1] == '1' || code[1] == '2'
            else -> false
        }

        private val SAFETY_WORDS = Regex(
            "\\b(airbags?|deployment|squib|restraint|brakes?|braking|abs|steering|seat ?belt|pretensioner|" +
                "oil pressure|over ?temp\\w*|overheat\\w*|coolant temp\\w*|fuel (leak|pressure)|stability|traction|throttle actuator)\\b",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Community data never says a code is minor: either "get it checked promptly" (limp mode or a
         * safety system) or "not sure how serious". Only Penelope's reviewed text uses milder levels.
         */
        fun severityOf(d: KnowledgeDtc): DtcSeverity =
            if (d.limpModePossible || SAFETY_WORDS.containsMatchIn(d.title) || SAFETY_WORDS.containsMatchIn(d.description)) DtcSeverity.SEVERE
            else DtcSeverity.UNKNOWN

        fun severityOf(d: ManufacturerDtc): DtcSeverity = if (SAFETY_WORDS.containsMatchIn(d.description)) DtcSeverity.SEVERE else DtcSeverity.UNKNOWN

        fun fromGeneric(d: KnowledgeDtc): DtcInfo {
            val causes = d.causes.take(4).map { it.label }
            val simple = buildString {
                append("Your car's computer stored ${d.code}: ${d.title.trimEnd('.')}.")
                if (causes.isNotEmpty()) append(" A common cause is ${causes.first().replaceFirstChar(Char::lowercase).trimEnd('.')}.")
                append(" A code is a clue, not a diagnosis, so it's worth confirming before replacing parts.")
            }
            val diy = buildString {
                if (causes.isNotEmpty()) append("Common causes, most likely first: ${causes.joinToString("; ")}.")
                when (d.diyPossible) {
                    false -> append(" This one usually needs a mechanic.")
                    true -> append(" Some of these can be checked at home.")
                    null -> Unit
                }
            }.trim()
            return DtcInfo(
                code = d.code,
                description = d.title,
                simple = simple,
                diyChecks = diy.ifEmpty { "No common causes are listed for this code." },
                technical = d.description.ifBlank { d.title },
                severity = severityOf(d),
                headline = d.title,
                source = "OBDex community catalog (CC0)" + (d.reviewed?.let { ", reviewed $it" } ?: ""),
            )
        }

        fun fromManufacturer(d: ManufacturerDtc): DtcInfo = DtcInfo(
            code = d.code,
            description = d.description,
            simple = "On ${d.make.lowercase().replaceFirstChar(Char::uppercase)} vehicles, ${d.code} means: ${d.description.trimEnd('.')}. " +
                "This is a manufacturer-specific code from community data, so a shop with brand software can confirm it.",
            diyChecks = "Manufacturer-specific codes are best confirmed with brand diagnostic software before any repair.",
            technical = "${d.make} manufacturer-specific definition (community data): ${d.description}",
            severity = severityOf(d),
            headline = d.description,
            source = "Wal33D dtc-database (MIT), ${d.make}-specific",
        )
    }
}

/** Picks the triage guide that best matches what the user described. */
object TriageMatcher {
    private val STOP = setOf("the", "my", "a", "an", "is", "and", "or", "of", "to", "when", "it", "car", "engine", "in", "on", "at", "with", "not", "has", "have", "i")

    private val SHORT = setOf("ac")

    private fun words(text: String) = text.lowercase().replace("a/c", "ac").split(Regex("[^a-z]+"))
        .filter { (it.length > 2 || it in SHORT) && it !in STOP }.map { it.removeSuffix("s") }.toSet()

    /** [minOverlap]: 1 for a recognized symptom, 2 for anything else, so unrelated questions get no guide. */
    fun best(guides: List<TriageGuide>, utterance: String, minOverlap: Int = 1): TriageGuide? {
        val said = words(utterance)
        if (said.isEmpty()) return null
        return guides.map { g -> g to (words(g.symptom + " " + g.id.replace('_', ' ')) intersect said).size }
            .filter { it.second >= minOverlap }
            .maxByOrNull { it.second }
            ?.first
    }
}

/** Plain-language recall and crash-rating answers. */
object VehicleRecords {
    const val VIN_CHECK = "To see whether any apply to your car and are still open, check your VIN at nhtsa.gov/recalls."

    fun recallAnswer(vehicleName: String, count: Int, sample: List<RecallCampaign>, snapshot: String?): String {
        val asOf = snapshot?.let { " (NHTSA data from $it)" }.orEmpty()
        if (count == 0) {
            return "I couldn't find recall campaigns for the $vehicleName in my offline data$asOf. Model names don't always match, " +
                "so that doesn't mean there are none. $VIN_CHECK"
        }
        val examples = sample.take(3).joinToString("; ") { r ->
            listOfNotNull(r.component?.lowercase()?.replaceFirstChar(Char::uppercase), r.consequence?.takeIf { it.isNotBlank() }?.let { "risk: ${it.trimEnd('.')}" })
                .joinToString(", ") + " (campaign ${r.campaign})"
        }
        val plural = if (count == 1) "recall campaign" else "recall campaigns"
        return "NHTSA lists $count $plural for the $vehicleName$asOf. For example: $examples. These cover the model, not necessarily your car. $VIN_CHECK"
    }

    fun ratingAnswer(vehicleName: String, ratings: List<SafetyRating>): String {
        val rated = ratings.filter { it.overall != null || it.rollover != null }
        if (rated.isEmpty()) return "I don't have NHTSA crash-test ratings for the $vehicleName in my offline data."
        val overall = rated.mapNotNull { it.overall }.distinct()
        if (overall.size > 1) {
            return "NHTSA rated versions of the $vehicleName between ${overall.min()} and ${overall.max()} out of 5 stars overall, " +
                "depending on body style and drive type. Tell me which one you have and I can be more specific."
        }
        val best = rated.first()
        val parts = listOfNotNull(
            best.overall?.let { "$it out of 5 stars overall" },
            best.frontal?.let { "$it for frontal crashes" },
            best.side?.let { "$it for side crashes" },
            best.rollover?.let { "$it for rollover" },
        )
        val variant = listOfNotNull(best.body, best.drive).joinToString(" ").takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return "NHTSA rated the $vehicleName$variant ${parts.joinToString(", ")}. Ratings can differ by body style and drive type."
    }
}
