package com.aicarcompanion.app.assistant.voice

import com.aicarcompanion.app.assistant.persona.AssistantProfiles
import com.aicarcompanion.app.assistant.persona.VoiceChoice
import com.aicarcompanion.app.assistant.persona.VoiceGender
import com.aicarcompanion.app.data.local.AppSettings
import com.aicarcompanion.app.data.local.VoiceSource
import java.util.Locale

/** What the speech engine should be asked to use. */
sealed interface VoiceRequest {
    /** The engine's own default voice for the language. */
    data object EngineDefault : VoiceRequest

    /** A specific installed voice; if it is gone, [fallback] is used and the user is told. */
    data class Named(val name: String, val fallback: Gendered) : VoiceRequest

    /** The [slot]-th voice of a gender, when the phone has voices it can tell apart by gender. */
    data class Gendered(val gender: VoiceGender, val slot: Int, val preferOnline: Boolean = false) : VoiceRequest
}

/** The outcome of choosing a voice from what is actually installed. Never claims a gender it doesn't know. */
sealed interface VoiceResolution {
    /** Short, honest description for the UI ("Female voice 2 · works offline"). */
    val label: String

    data class Voice(
        val voiceName: String,
        override val label: String,
        /** Null when the phone doesn't say; never guessed. */
        val gender: VoiceGender?,
        /** Fewer voices of this gender are installed than profiles, so some profiles share a voice. */
        val shared: Boolean,
        val requiresNetwork: Boolean,
        /** Why this voice was used instead of the one asked for, if it differs. */
        val notice: String? = null,
    ) : VoiceResolution

    /** No suitable voice: the engine's default voice is used and the reason is shown. */
    data class FallbackDefault(val reason: String, val missingGender: VoiceGender? = null) : VoiceResolution {
        override val label: String get() = "Phone default voice"
    }
}

/** Pure voice selection (JVM-testable). Uses only voice names and features the phone reports. */
object VoiceSelector {
    // HYPOTHESIS: Google TTS voice codes by gender. Verify by ear on the device; unknown codes stay unlabeled.
    private val googleFemale = setOf("sfg", "tpf", "tpc", "iob", "iog")
    private val googleMale = setOf("iom", "iol", "tpd")
    private val samsung = Regex("(?i)SMT([fm])\\d+")
    private val google = Regex("-x-([a-z]{3})-")

    /** The gender the phone's own data says this voice has, or null when it doesn't say. */
    fun genderOf(raw: VoiceTuning.RawVoice): VoiceGender? {
        raw.features.map { it.lowercase() }.forEach { f ->
            if (f.contains("female")) return VoiceGender.FEMALE
            if (f.contains("male")) return VoiceGender.MALE
        }
        samsung.find(raw.name)?.let { return if (it.groupValues[1].equals("f", true)) VoiceGender.FEMALE else VoiceGender.MALE }
        google.find(raw.name.lowercase())?.let {
            val code = it.groupValues[1]
            return when (code) {
                in googleFemale -> VoiceGender.FEMALE
                in googleMale -> VoiceGender.MALE
                else -> null
            }
        }
        return null
    }

    /** Installed voices for the language in one fixed, order-independent order. */
    fun candidates(voices: List<VoiceTuning.RawVoice>, locale: Locale): List<VoiceTuning.RawVoice> =
        voices.filter { it.locale.language == locale.language && "notInstalled" !in it.features }
            .sortedWith(
                compareBy<VoiceTuning.RawVoice> { it.locale.country != locale.country }
                    .thenBy { it.requiresNetwork }
                    .thenByDescending { it.quality }
                    .thenBy { it.name },
            )

    /** Which "Female voice N" / "Male voice N" / "Unlabeled voice N" this is: its place among same-gender voices in [sorted]. */
    fun ordinalOf(voice: VoiceTuning.RawVoice, sorted: List<VoiceTuning.RawVoice>): Int {
        val g = genderOf(voice)
        return sorted.takeWhile { it.name != voice.name }.count { genderOf(it) == g } + 1
    }

    private fun profilesOf(gender: VoiceGender) = AssistantProfiles.all.count { it.voiceGender == gender }

    private fun genderWord(g: VoiceGender?) = when (g) {
        VoiceGender.FEMALE -> "Female"
        VoiceGender.MALE -> "Male"
        null -> "Unlabeled"
    }

    private fun tag(v: VoiceTuning.RawVoice) = if (v.requiresNetwork) "needs internet" else "works offline"

    fun resolve(request: VoiceRequest, voices: List<VoiceTuning.RawVoice>, locale: Locale): VoiceResolution {
        val sorted = candidates(voices, locale)
        return when (request) {
            VoiceRequest.EngineDefault -> VoiceResolution.FallbackDefault("Using the phone's default voice.")
            is VoiceRequest.Named -> {
                val index = sorted.indexOfFirst { it.name == request.name }
                if (index >= 0) {
                    val v = sorted[index]
                    val g = genderOf(v)
                    val n = ordinalOf(v, sorted)
                    VoiceResolution.Voice(v.name, "${genderWord(g)} voice $n · ${tag(v)}", g, shared = false, requiresNetwork = v.requiresNetwork)
                } else {
                    gendered(request.fallback, sorted, "Your chosen voice isn't installed anymore")
                }
            }
            is VoiceRequest.Gendered -> gendered(request, sorted, null)
        }
    }

    private fun gendered(request: VoiceRequest.Gendered, sorted: List<VoiceTuning.RawVoice>, notice: String?): VoiceResolution {
        val ofGender = sorted.filter { genderOf(it) == request.gender }
        val offline = ofGender.filter { !it.requiresNetwork }
        val online = ofGender.filter { it.requiresNetwork }
        // Online preferred: network voices first, offline only if there are none. Otherwise the reverse. Never an unknown gender.
        val pool = if (request.preferOnline) online.ifEmpty { offline } else offline.ifEmpty { online }
        if (pool.isEmpty()) {
            val word = if (request.gender == VoiceGender.MALE) "male" else "female"
            return VoiceResolution.FallbackDefault(notice?.let { "$it. " }.orEmpty() + "No $word voice is installed on this phone", request.gender)
        }
        val index = request.slot % pool.size
        val v = pool[index]
        return VoiceResolution.Voice(
            voiceName = v.name,
            label = "${genderWord(request.gender)} voice ${ordinalOf(v, sorted)} · ${tag(v)}",
            gender = request.gender,
            shared = pool.size < profilesOf(request.gender),
            requiresNetwork = v.requiresNetwork,
            notice = notice,
        )
    }
}

/** Builds the speech configuration from settings. The binder, conversation and previews all use this one path. */
object VoiceConfigFactory {
    fun request(settings: AppSettings, override: VoiceChoice? = null): VoiceRequest {
        fun forProfile(id: String): VoiceRequest.Gendered =
            AssistantProfiles.byId(id).let {
                VoiceRequest.Gendered(it.voiceGender, it.voiceSlot, settings.voiceSource == VoiceSource.ONLINE)
            }
        return when (val choice = override ?: settings.voiceChoice) {
            VoiceChoice.MatchPersonality -> forProfile(settings.assistantProfileId)
            is VoiceChoice.Profile -> forProfile(choice.id)
            is VoiceChoice.Installed -> VoiceRequest.Named(choice.voiceName, forProfile(settings.assistantProfileId))
        }
    }

    fun from(settings: AppSettings, override: VoiceChoice? = null): VoiceConfig = VoiceConfig(
        rate = VoiceTuning.rate(settings.speechRate),
        pitch = VoiceTuning.pitch(settings.speechPitch),
        engine = settings.ttsEngine,
        voice = request(settings, override),
    )
}
