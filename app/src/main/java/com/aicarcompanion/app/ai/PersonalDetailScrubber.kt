package com.aicarcompanion.app.ai

/**
 * Removes personal details from owner-entered text before it is added to Gemini's background facts
 * (owner decision 2026-11-21, option 2: automatic scrubbing).
 *
 * Removed: email addresses, web links, phone numbers, street addresses, VIN-like codes and licence
 * plates written after a word like "plate". Kept: the car words that make answers useful, such as
 * trouble codes (P0455), engine codes (N52B30), sizes (3.0L), mileage (98,000 miles) and dates.
 *
 * Limits: people's names can't be recognized reliably and are not removed; a plate is recognized only
 * after a word like "plate" or "tag" and when it contains a digit; phone numbers are matched in North
 * American formats. Pure function: nothing is logged.
 */
object PersonalDetailScrubber {
    const val MARKER = "[removed]"

    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val URL = Regex("""(?i)\b(?:https?://|www\.)\S+""")

    /** North American style numbers: 10 digits with optional +1, brackets and separators, and 7-digit local numbers (555-1234). */
    private val PHONE = Regex("""(?<![\w.])(?:\+?\d{1,3}[\s.-]?)?(?:\(\d{3}\)|\d{3})[\s.-]?\d{3}[\s.-]?\d{4}(?![\w])|(?<![\w.-])\d{3}[-.]\d{4}(?![\w-])""")

    /** "12 Oak St", "1600 Pennsylvania Ave", "4500 E Main Street": a house number, capitalized words, a street type. */
    private val ADDRESS = Regex(
        """\b\d{1,6}\s+(?:[A-Z][A-Za-z.'-]*\s+){1,4}(?:Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Drive|Dr|Lane|Ln|Way|Court|Ct|Place|Pl|Circle|Cir|Parkway|Pkwy|Highway|Hwy|Terrace|Trail)\b\.?""",
        RegexOption.IGNORE_CASE,
    )

    /** 17 VIN characters mixing letters and digits. */
    private val VIN = Regex("""(?i)\b(?=[A-HJ-NPR-Z0-9]*\d)(?=[A-HJ-NPR-Z0-9]*[A-HJ-NPR-Z])[A-HJ-NPR-Z0-9]{17}\b""")

    /** A licence plate written after a word that says so: "plate ABC 1234", "tag: 7XYZ123", "licence plate no. KX12 ABC". */
    private val PLATE = Regex("""(?i)\b((?:licen[cs]e\s+)?(?:plate|tag|registration)\b(?:\s*(?:number|no\.?|#|:))*\s*)(?=[A-Z0-9 -]{0,9}?\d)[A-Z0-9](?:[A-Z0-9 -]{0,8}[A-Z0-9])?(?=$|[\s.,;!?)])""")

    /** "4 Wheel Drive" is a car term, not an address. */
    private val CAR_TERMS = Regex("""(?i)\bwheel\s+drive\b""")

    fun scrub(text: String): String = text
        .replace(EMAIL, MARKER)
        .replace(URL, MARKER)
        .replace(VIN, MARKER)
        .replace(PLATE) { it.groupValues[1] + MARKER }
        .replace(ADDRESS) { if (CAR_TERMS.containsMatchIn(it.value)) it.value else MARKER }
        .replace(PHONE, MARKER)
}
