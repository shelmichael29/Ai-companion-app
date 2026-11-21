package com.aicarcompanion.app.vin

import com.aicarcompanion.app.data.vehicledb.VinValidator

/*
 * Turning camera text into a VIN, without ever picking one the user hasn't confirmed.
 * Platform-neutral (no android.*): the Android camera code feeds recognized lines in.
 *
 * Rules:
 * - Only whole 17-character tokens (or a whole line) count. Random 17-character windows inside
 *   longer text would pass the check digit by chance about 1 time in 11.
 * - VINs never contain I, O or Q, so those are always misreads: I→1, O→0, Q→0, and the
 *   candidate is marked as corrected.
 * - A VIN is "locked" only after the same value is seen in several frames and nothing else
 *   competes. Locking only highlights it: the user still confirms or corrects it.
 * - A failed check digit isn't an error (many non-U.S. VINs don't use it) but is flagged as
 *   uncertain so the user compares every character.
 */

data class ScanBox(val left: Int, val top: Int, val right: Int, val bottom: Int)

data class RecognizedLine(val text: String, val box: ScanBox?)

data class VinCandidate(
    val vin: String,
    val checkDigitValid: Boolean,
    /** How many characters were changed from the recognized text (I/O/Q → 1/0/0). */
    val correctedChars: Int,
    val box: ScanBox?,
) {
    val uncertain: Boolean get() = !checkDigitValid || correctedChars > 0
}

object VinTextExtractor {
    private val NON_ALNUM = Regex("[^A-Za-z0-9]+")

    fun candidates(lines: List<RecognizedLine>): List<VinCandidate> {
        val out = LinkedHashMap<String, VinCandidate>()
        for (line in lines) {
            val tokens = line.text.uppercase().split(NON_ALNUM).filter { it.isNotEmpty() }
            val options = buildList {
                tokens.filter { it.length == 17 }.forEach(::add)
                // A VIN printed in groups ("WBAFR1C5 6BC000001"), optionally after a "VIN" label.
                val joined = tokens.dropWhile { it == "VIN" }.joinToString("")
                if (joined.length == 17) add(joined)
                // "VIN" glued to the number with no separator.
                tokens.firstOrNull()?.takeIf { it.startsWith("VIN") && it.length == 20 }?.let { add(it.removePrefix("VIN")) }
            }
            for (raw in options) {
                val fixed = raw.map { when (it) { 'I' -> '1'; 'O', 'Q' -> '0'; else -> it } }.joinToString("")
                val valid = VinValidator.validate(fixed) as? VinValidator.Result.Valid ?: continue
                val corrected = raw.indices.count { raw[it] != fixed[it] }
                val candidate = VinCandidate(valid.vin, valid.checkDigitValid, corrected, line.box)
                // Prefer the sighting that needed no correction.
                val existing = out[valid.vin]
                if (existing == null || corrected < existing.correctedChars) out[valid.vin] = candidate
            }
        }
        return out.values.toList()
    }
}

/** What the scanner knows right now. */
sealed interface ScanState {
    data object Searching : ScanState
    /** Something VIN-shaped is in view, but not yet seen steadily. */
    data class Reading(val candidate: VinCandidate) : ScanState
    /** Seen steadily and unambiguously. The user must still confirm it. */
    data class Locked(val candidate: VinCandidate) : ScanState
    /** Different VINs keep appearing: don't pick any. */
    data object Conflict : ScanState
}

class VinScanTracker(
    private val window: Int = 8,
    private val framesToLock: Int = 3,
) {
    private val history = ArrayDeque<List<VinCandidate>>()

    fun reset() = history.clear()

    fun observe(frame: List<VinCandidate>): ScanState {
        history.addLast(frame.distinctBy { it.vin })
        while (history.size > window) history.removeFirst()
        val seen = history.flatten().groupBy { it.vin }
        if (seen.isEmpty()) return ScanState.Searching
        val ranked = seen.entries.sortedByDescending { it.value.size }
        val leader = ranked.first()
        val newest = leader.value.last()
        val rival = ranked.getOrNull(1)
        // Another VIN seen in at least 2 recent frames means the picture is ambiguous.
        if (rival != null && rival.value.size >= 2 && leader.value.size < rival.value.size + framesToLock) return ScanState.Conflict
        return if (leader.value.size >= framesToLock) ScanState.Locked(newest) else ScanState.Reading(newest)
    }
}

/** Maps a box in the analyzed image to the preview it's drawn over (preview crops to fill). */
object ScanGeometry {
    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** [imageWidth]/[imageHeight] are the upright (already rotated) analyzed image dimensions. */
    fun toView(box: ScanBox, imageWidth: Int, imageHeight: Int, viewWidth: Float, viewHeight: Float): Rect {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0f || viewHeight <= 0f) return Rect(0f, 0f, 0f, 0f)
        val scale = maxOf(viewWidth / imageWidth, viewHeight / imageHeight) // FILL_CENTER
        val dx = (viewWidth - imageWidth * scale) / 2f
        val dy = (viewHeight - imageHeight * scale) / 2f
        return Rect(box.left * scale + dx, box.top * scale + dy, box.right * scale + dx, box.bottom * scale + dy)
    }
}
