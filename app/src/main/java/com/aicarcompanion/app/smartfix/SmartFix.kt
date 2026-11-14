package com.aicarcompanion.app.smartfix

import com.aicarcompanion.app.domain.model.Vehicle

/*
 * SmartFix — FUTURE vehicle-specific repair recommendations. Interfaces only.
 *
 * Rules any implementation must follow:
 *  - every remedy cites where it came from (TSB, documented failure pattern, repair outcomes);
 *  - numbers such as success rates exist only with a named source ([SourcedFigure]);
 *  - confirmatory tests are offered before suggesting parts replacement.
 */

enum class RemedySourceType(val label: String) {
    TECHNICAL_SERVICE_BULLETIN("Manufacturer technical service bulletin"),
    DOCUMENTED_COMMON_FAILURE("Documented common failure"),
    REPAIR_OUTCOME_DATA("Recorded repair outcomes"),
}

data class SourceReference(val type: RemedySourceType, val title: String, val reference: String?)

/** A figure that may only be shown alongside its source. */
data class SourcedFigure(val value: Double, val description: String, val source: SourceReference)

data class RemedyCandidate(
    val title: String,
    val plainExplanation: String,
    val confirmatoryTests: List<String>,
    val sources: List<SourceReference>,
    val reportedSuccess: SourcedFigure? = null,
) {
    init { require(sources.isNotEmpty()) { "A remedy must cite at least one source." } }
}

sealed interface RemedyLookup {
    /** Up to three candidates, ordered by the source's own ranking (not invented by the app). */
    data class Found(val code: String, val candidates: List<RemedyCandidate>) : RemedyLookup
    data object NotAvailable : RemedyLookup
}

interface RepairKnowledgeSource {
    suspend fun likelyRemedies(vehicle: Vehicle, code: String): RemedyLookup
}

/** Shipped default: SmartFix data isn't available yet. */
object NoRepairKnowledge : RepairKnowledgeSource {
    override suspend fun likelyRemedies(vehicle: Vehicle, code: String) = RemedyLookup.NotAvailable
}
