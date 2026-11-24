package com.aicarcompanion.app.assistant.voice

import com.aicarcompanion.app.assistant.persona.VoiceGender
import com.aicarcompanion.app.data.local.AppSettings
import java.util.Locale

/** Pure helpers for voice configuration (JVM-testable). */
object VoiceTuning {
    /** A callback for an older TTS request must not complete the current one. */
    fun isCurrentUtterance(activeId: String, callbackId: String): Boolean =
        callbackId.startsWith("$activeId-")

    fun rate(value: Float) = value.coerceIn(AppSettings.RATE_RANGE)
    fun pitch(value: Float) = value.coerceIn(AppSettings.PITCH_RANGE)

    data class RawVoice(
        val name: String,
        val locale: Locale,
        val requiresNetwork: Boolean,
        /** android.speech.tts.Voice.QUALITY_* (100 = very low … 500 = very high). */
        val quality: Int,
        val features: Set<String>,
    )

    /**
     * Voices matching the user's language, offline and higher quality first, with friendly
     * labels ("English (United States) · Female voice 2 · works offline"). Gender is only shown when
     * the phone reports it; otherwise the voice is "Unlabeled". Engine voices that are
     * flagged as not installed are skipped.
     */
    fun voiceOptions(voices: List<RawVoice>, locale: Locale): List<VoiceOption> {
        val sameLanguage = voices.filter { it.locale.language == locale.language && "notInstalled" !in it.features }
        val sorted = sameLanguage.sortedWith(
            compareBy<RawVoice> { it.locale.country != locale.country }
                .thenBy { it.requiresNetwork }
                .thenByDescending { it.quality }
                .thenBy { it.name },
        )
        val counters = mutableMapOf<String, Int>()
        return sorted.map { v ->
            val place = v.locale.getDisplayName(Locale.getDefault())
            val gender = VoiceSelector.genderOf(v)
            val word = when (gender) {
                VoiceGender.FEMALE -> "Female"
                VoiceGender.MALE -> "Male"
                null -> "Unlabeled"
            }
            val n = counters.merge("$place|$word", 1, Int::plus)!!
            val tags = listOfNotNull(
                if (v.requiresNetwork) "needs internet" else "works offline",
                "higher quality".takeIf { v.quality >= 400 },
            )
            VoiceOption(v.name, "$place · $word voice $n · ${tags.joinToString(", ")}", v.requiresNetwork, v.quality >= 400)
        }
    }

    /** Splits long text at sentence boundaries so no chunk exceeds the engine's limit. */
    fun chunk(text: String, maxLength: Int): List<String> {
        if (text.length <= maxLength) return listOf(text)
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (s in sentences) {
            if (current.isNotEmpty() && current.length + 1 + s.length > maxLength) {
                chunks += current.toString(); current.clear()
            }
            if (s.length > maxLength) {
                s.chunked(maxLength).forEach { chunks += it }
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(s)
            }
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }
}
