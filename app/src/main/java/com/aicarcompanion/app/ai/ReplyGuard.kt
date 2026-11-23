package com.aicarcompanion.app.ai

/**
 * Cleans Gemini output for speech and checks it for things Penelope must never invent.
 * A failed check means the app answers with its deterministic logic instead.
 */
object ReplyGuard {
    enum class Violation { INVENTED_CODE, UNVERIFIED_READING, EMPTY }

    private val CODE = Regex("\\b[PCBU][0-3][0-9A-F]{3}\\b", RegexOption.IGNORE_CASE)
    private val NUMBER_UNIT = Regex("(\\d+(?:\\.\\d+)?)\\s*(°\\s*[FC]?|degrees?|rpm|volts?|v\\b|psi|kpa|mph|km/h|percent|%)", RegexOption.IGNORE_CASE)
    /** Words that make a number a statement about this car now. */
    private val CLAIMS_CURRENT = Regex("\\b(your|currently|right now|is reading|reads|reading)\\b", RegexOption.IGNORE_CASE)
    /** "Coolant is 130 degrees.": a bare present-tense value is a claim too. */
    private val STATES_VALUE = Regex("\\b(is|are|was|reads?|sits? at)\\s+(now\\s+)?(about|around|roughly|approximately|nearly|almost)?\\s*\\d", RegexOption.IGNORE_CASE)
    /** Sensor labels followed by a numeric value also assert a reading, even without "is". */
    private val LABELED_READING = Regex(
        """\b(?:coolant|oil|battery|engine|vehicle|transmission|intake|boost|fuel|rpm|speed|voltage|temperature|pressure)\b[^.!?\n]{0,35}[:=]\s*(?:about|around|roughly|approximately)?\s*\d""",
        RegexOption.IGNORE_CASE,
    )
    /** General knowledge ("normally runs between…"). Hedges like "about" or "around" don't count. */
    private val NORMATIVE = Regex("\\b(normal|normally|typical|typically|usually|should|most cars|most engines|ideal|generally|healthy|fully charged|between|range|at rest|at idle|boils|freezes|recommended|rated)\\b", RegexOption.IGNORE_CASE)
    /** "Your coolant is at 130 degrees": a value stated for this car, however it's worded. */
    private val YOUR_VALUE = Regex("\\byour\\b[^.!?]*\\b(is|are|at|reads?)\\s+(now\\s+)?(about|around|roughly|approximately|nearly|almost)?\\s*\\d", RegexOption.IGNORE_CASE)
    /** Explicitly live wording: never excused by general words. */
    private val CLAIMS_NOW = Regex("\\b(currently|right now|is reading|reads|reading)\\b", RegexOption.IGNORE_CASE)

    private enum class UnitKind { TEMPERATURE, RPM, VOLTAGE, PRESSURE, SPEED, PERCENT }

    private fun kind(unit: String): UnitKind {
        val u = unit.lowercase().replace(" ", "")
        return when {
            u.startsWith("°") || u.startsWith("degree") -> UnitKind.TEMPERATURE
            u == "rpm" -> UnitKind.RPM
            u.startsWith("volt") || u == "v" -> UnitKind.VOLTAGE
            u == "psi" || u == "kpa" -> UnitKind.PRESSURE
            u == "mph" || u == "km/h" -> UnitKind.SPEED
            else -> UnitKind.PERCENT
        }
    }

    /** Number-and-unit pairs in the app's verified result (codes removed first, so P0300 isn't "300"). */
    private fun verifiedValues(facts: String): Set<Pair<String, UnitKind>> =
        NUMBER_UNIT.findAll(CODE.replace(facts, " ")).map { normalize(it.groupValues[1]) to kind(it.groupValues[2]) }.toSet()

    /** Removes markdown and list formatting and trims to a speakable length at a sentence boundary. */
    fun clean(raw: String, maxChars: Int = 700): String {
        var t = raw
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("[*_`#>]+"), "")
            .replace(Regex("(?m)^\\s*([-•]|\\d+[.)])\\s+"), "")
            .replace(Regex("\\s*\\n+\\s*"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        if (t.length > maxChars) {
            val cut = t.substring(0, maxChars)
            val end = maxOf(cut.lastIndexOf(". "), cut.lastIndexOf("? "), cut.lastIndexOf("! "))
            t = if (end > maxChars / 3) cut.substring(0, end + 1) else cut.trimEnd() + "…"
        }
        return t
    }

    /**
     * Returns a violation if the reply invents codes or states a reading the app didn't provide.
     * A reading is allowed only if the same number with the same kind of unit is in [verifiedFacts]
     * (the result the app read for this question): that's Gemini explaining real data.
     */
    fun check(text: String, allowedCodes: Set<String>, verifiedFacts: String = ""): Violation? {
        if (text.isBlank()) return Violation.EMPTY
        val allowed = allowedCodes.map { it.uppercase() }.toSet()
        if (CODE.findAll(text).any { it.value.uppercase() !in allowed }) return Violation.INVENTED_CODE
        val verified = verifiedValues(verifiedFacts)
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        val invented = sentences.any { s ->
            val values = NUMBER_UNIT.findAll(s).toList()
            if (values.isEmpty()) return@any false
            val labeledReading = LABELED_READING.containsMatchIn(s)
            val claim = CLAIMS_CURRENT.containsMatchIn(s) || STATES_VALUE.containsMatchIn(s) || labeledReading
            val generalKnowledge = NORMATIVE.containsMatchIn(s) && !CLAIMS_NOW.containsMatchIn(s) &&
                !YOUR_VALUE.containsMatchIn(s) && !labeledReading
            claim && !generalKnowledge && values.any { (normalize(it.groupValues[1]) to kind(it.groupValues[2])) !in verified }
        }
        return if (invented) Violation.UNVERIFIED_READING else null
    }

    private fun normalize(number: String) = number.toBigDecimal().stripTrailingZeros().toPlainString()
}
