package com.aicarcompanion.app.ai

/**
 * Cleans Gemini output for speech and checks it for things Penelope must never invent.
 * A failed check means the app answers with its deterministic logic instead.
 */
object ReplyGuard {
    enum class Violation { INVENTED_CODE, UNVERIFIED_READING, EMPTY }

    private val CODE = Regex("\\b[PCBU][0-3][0-9A-F]{3}\\b")
    private val NUMBER_UNIT = Regex("\\d+(\\.\\d+)?\\s*(°\\s*[FC]?|degrees|rpm|volts?|v\\b|psi|kpa|mph|km/h|percent|%)", RegexOption.IGNORE_CASE)
    private val CLAIMS_CURRENT = Regex("\\b(your|currently|right now|is reading|reads|reading)\\b", RegexOption.IGNORE_CASE)
    private val GENERAL = Regex("\\b(normal|normally|typical|typically|usually|between|range|should|most cars|most engines|ideal|generally|around|about)\\b", RegexOption.IGNORE_CASE)

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

    /** Returns a violation if the reply invents codes or states a live-looking reading the app didn't provide. */
    fun check(text: String, allowedCodes: Set<String>): Violation? {
        if (text.isBlank()) return Violation.EMPTY
        if (CODE.findAll(text).any { it.value !in allowedCodes }) return Violation.INVENTED_CODE
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        if (sentences.any { NUMBER_UNIT.containsMatchIn(it) && CLAIMS_CURRENT.containsMatchIn(it) && !GENERAL.containsMatchIn(it) }) {
            return Violation.UNVERIFIED_READING
        }
        return null
    }
}
