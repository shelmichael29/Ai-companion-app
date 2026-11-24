package com.aicarcompanion.app.assistant.persona

import java.text.Normalizer

/**
 * Cleans a user-chosen assistant name. The name goes into the Gemini system instruction and the
 * speech parser, so it must be a short single-line label and never able to carry instructions.
 * Names are never logged.
 */
object AssistantNamePolicy {
    const val MAX_CODE_POINTS = 24

    private val reserved = setOf(
        "stop", "pause", "cancel", "yes", "no", "send", "call", "help", "repeat", "goodbye",
    )
    private val allowed = Regex("""[\p{L}\p{M}\p{Nd} '\-.]""")

    /** Returns the cleaned name, or null when nothing usable is left (use the profile name instead). */
    fun sanitize(raw: String?): String? {
        if (raw == null) return null
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC).replace('\u2019', '\'')
        val kept = normalized.codePoints().toArray().filter { cp ->
            // Line breaks and tabs count as spaces; other control/format characters are dropped.
            if (cp == '\n'.code || cp == '\r'.code || cp == '\t'.code) return@filter true
            val type = Character.getType(cp)
            type != Character.CONTROL.toInt() && type != Character.FORMAT.toInt() &&
                type != Character.PRIVATE_USE.toInt() && type != Character.UNASSIGNED.toInt() &&
                type != Character.SURROGATE.toInt()
        }
        val filtered = kept.map { if (it == '\n'.code || it == '\r'.code || it == '\t'.code) ' '.code else it }
            .filter { allowed.matches(String(Character.toChars(it))) }
        val collapsed = String(filtered.toIntArray(), 0, filtered.size).replace(Regex("""\s+"""), " ").trim()
        val points = collapsed.codePoints().toArray()
        val capped = String(points, 0, minOf(points.size, MAX_CODE_POINTS)).trim()
        if (capped.codePoints().noneMatch { Character.isLetter(it) }) return null
        // "Stop." or "help-" are still the command word.
        if (capped.lowercase().trim { it in ".'- " } in reserved) return null
        return capped
    }
}
