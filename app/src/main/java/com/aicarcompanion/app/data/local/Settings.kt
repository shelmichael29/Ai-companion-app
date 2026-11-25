package com.aicarcompanion.app.data.local

import androidx.core.content.edit
import android.content.Context
import android.content.SharedPreferences
import com.aicarcompanion.app.assistant.persona.AssistantNamePolicy
import com.aicarcompanion.app.assistant.persona.AssistantProfile
import com.aicarcompanion.app.assistant.persona.AssistantProfiles
import com.aicarcompanion.app.assistant.persona.VoiceChoice
import com.aicarcompanion.app.dashboard.DashboardItem
import com.aicarcompanion.app.dashboard.DashboardSettings
import com.aicarcompanion.app.dashboard.HomeLayout
import com.aicarcompanion.app.dashboard.LevelLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** How much detail Penelope gives. Reasoning is identical across levels; only presentation changes. */
enum class ExplanationLevel(val label: String, val description: String) {
    EVERYDAY("Everyday driver", "Simple explanations and clear next steps. No car knowledge needed."),
    DIY("DIY enthusiast", "Adds things you can check yourself and optional sensor readings."),
    /** Internal only since Phase 1A: never offered to the user. A stored ADVANCED value is migrated to [DIY]. */
    ADVANCED("Advanced / professional", "Technical terms, code definitions and all available live data."),
    ;

    /** The level the user sees and the app treats as selected; [ADVANCED] maps to [DIY]. */
    fun toSelectable(): ExplanationLevel = if (this == ADVANCED) DIY else this

    companion object {
        /** The only levels the user can choose, in display order. */
        val selectable: List<ExplanationLevel> = listOf(EVERYDAY, DIY)
    }
}

enum class ThemeMode(val label: String) { SYSTEM("Match phone"), LIGHT("Light"), DARK("Dark (default)") }

/** Accent color family. Independent of [ThemeMode]: each style has a dark and a light version. */
enum class ColorStyle(val label: String, val description: String) {
    MIDNIGHT("Midnight", "Deep navy with cyan and blue light (default)."),
    AMETHYST("Amethyst", "Black with electric purple and lavender light."),
}

/** User preferences. Small enough for SharedPreferences; no extra dependency needed. */
data class AppSettings(
    val activeVehicleId: String? = null,
    /** Which preset personality and voice is selected (see [AssistantProfiles]). Independent of [explanationLevel]. */
    val assistantProfileId: String = AssistantProfiles.DEFAULT_ID,
    /** A name the user chose instead of the profile's name; already sanitized. Never logged. */
    val customName: String? = null,
    /** Which voice to speak with; by default the one matching the personality. */
    val voiceChoice: VoiceChoice = VoiceChoice.MatchPersonality,
    /** When on, the dashboard and Penelope use labeled sample diagnostics. Off by default. */
    val demoDataEnabled: Boolean = false,
    /** Bluetooth address of the last OBD-II adapter the user connected to. */
    val lastAdapterAddress: String? = null,
    /** Text-to-speech speed multiplier (1.0 = natural). Independent of [speechPitch]. */
    val speechRate: Float = 1.0f,
    /** Text-to-speech pitch multiplier (1.0 = natural). Always applied explicitly. */
    val speechPitch: Float = 1.0f,
    /** TTS engine package (e.g. com.google.android.tts); null = phone default. */
    val ttsEngine: String? = null,
    /** Keep listening after each reply (hands-free conversation). On by default. */
    val continuousConversation: Boolean = true,
    val explanationLevel: ExplanationLevel = ExplanationLevel.EVERYDAY,
    /** Penelope is designed black-and-purple first; Light and Match phone remain available. */
    val themeMode: ThemeMode = ThemeMode.DARK,
    /** Accent colors: Midnight (navy and cyan) by default, or the original Amethyst purple. */
    val colorStyle: ColorStyle = ColorStyle.MIDNIGHT,
    /** Calmer visuals: static orb, no background motion. Android's "Remove animations" also applies. */
    val reduceMotion: Boolean = false,
    /** Use Gemini (via Firebase AI Logic) for conversation when the app is configured for it. */
    val aiEnabled: Boolean = true,
    /** Home-screen layout per knowledge level (Easy or Customizable). Independent of [explanationLevel]. */
    val dashboard: DashboardSettings = DashboardSettings(),
) {
    /** Settings hold the assistant name and the adapter address, so they never print themselves. */
    override fun toString() = "AppSettings(profile=$assistantProfileId, level=$explanationLevel)"

    /** The selected personality (unknown ids resolve to Penelope). */
    val profile: AssistantProfile get() = AssistantProfiles.byId(assistantProfileId)

    /** The name spoken, shown and listened for: the custom name if set, otherwise the profile's name. */
    val assistantName: String get() = customName ?: profile.name

    companion object {
        const val DEFAULT_ASSISTANT_NAME = "Penelope"
        val RATE_RANGE = 0.5f..1.5f
        val PITCH_RANGE = 0.75f..1.25f
    }
}

interface SettingsStore {
    val settings: StateFlow<AppSettings>
    fun update(transform: (AppSettings) -> AppSettings)
}

class InMemorySettingsStore(initial: AppSettings = AppSettings()) : SettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<AppSettings> = state.asStateFlow()
    override fun update(transform: (AppSettings) -> AppSettings) = state.update(transform)
}

/** Minimal key-value storage so the settings codec can be unit-tested without Android. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun getFloat(key: String): Float?
    fun getBoolean(key: String): Boolean?
    fun write(values: Map<String, Any?>)
}

class MapKeyValueStore : KeyValueStore {
    val map = mutableMapOf<String, Any?>()
    override fun getString(key: String) = map[key] as? String
    override fun getFloat(key: String) = map[key] as? Float
    override fun getBoolean(key: String) = map[key] as? Boolean
    override fun write(values: Map<String, Any?>) {
        values.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
    }
}

class SharedPreferencesKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String) = prefs.getString(key, null)
    override fun getFloat(key: String) = if (prefs.contains(key)) prefs.getFloat(key, 0f) else null
    override fun getBoolean(key: String) = if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    override fun write(values: Map<String, Any?>) {
        prefs.edit {
            values.forEach { (k, v) ->
                when (v) {
                    null -> remove(k)
                    is String -> putString(k, v)
                    is Float -> putFloat(k, v)
                    is Boolean -> putBoolean(k, v)
                    else -> error("Unsupported type for $k")
                }
            }
        }
    }
}

/** Reads and writes [AppSettings], clamping values so a bad stored value can't distort the voice. */
object SettingsCodec {
    private const val KEY_ACTIVE = "active_vehicle_id"
    private const val KEY_LEGACY_NAME = "assistant_name"
    private const val KEY_LEGACY_VOICE = "tts_voice"
    private const val KEY_PROFILE = "assistant_profile"
    private const val KEY_CUSTOM_NAME = "custom_assistant_name"
    private const val KEY_VOICE_CHOICE = "voice_choice"
    private const val KEY_SCHEMA = "settings_schema"
    private const val SCHEMA = "2"
    private const val KEY_DEMO = "demo_data_enabled"
    private const val KEY_ADAPTER = "last_adapter_address"
    private const val KEY_RATE = "speech_rate"
    private const val KEY_PITCH = "speech_pitch"
    private const val KEY_ENGINE = "tts_engine"
    private const val KEY_CONTINUOUS = "continuous_conversation"
    private const val KEY_LEVEL = "explanation_level"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_COLOR_STYLE = "color_style"
    private const val KEY_REDUCE_MOTION = "reduce_motion"
    private const val KEY_AI = "ai_enabled"
    private fun keyLayout(level: ExplanationLevel) = "home_layout_${level.name.lowercase()}"
    private fun keyItems(level: ExplanationLevel) = "home_items_${level.name.lowercase()}"

    /** True when the stored values predate the personality settings and should be rewritten. */
    fun needsMigration(store: KeyValueStore): Boolean = store.getString(KEY_SCHEMA) == null

    private fun storedLevel(store: KeyValueStore): ExplanationLevel? =
        ExplanationLevel.entries.firstOrNull { it.name == store.getString(KEY_LEVEL) }

    /** True when the stored level is the retired ADVANCED value and must become DIY. */
    fun needsLevelMigration(store: KeyValueStore): Boolean = storedLevel(store) == ExplanationLevel.ADVANCED

    /**
     * Moves a stored ADVANCED user to DIY in place. Writes only the level key (and the DIY layout keys when
     * the saved Advanced layout is carried over). It deliberately does not call [write], so the schema marker,
     * other settings and the Advanced layout keys are left exactly as they were. Safe to repeat.
     */
    fun migrateLevel(store: KeyValueStore, migrated: AppSettings) {
        val values = mutableMapOf<String, Any?>(KEY_LEVEL to ExplanationLevel.DIY.name)
        val diy = ExplanationLevel.DIY
        if (diy !in readDashboard(store).byLevel) {
            migrated.dashboard.byLevel[diy]?.let { choice ->
                values[keyLayout(diy)] = choice.layout.name
                values[keyItems(diy)] = choice.customItems?.joinToString(",") { it.name }
            }
        }
        store.write(values)
    }

    /** A saved Advanced home layout becomes the DIY layout when DIY has none of its own. */
    private fun carryAdvancedLayout(d: DashboardSettings): DashboardSettings {
        val advanced = d.byLevel[ExplanationLevel.ADVANCED] ?: return d
        if (ExplanationLevel.DIY in d.byLevel) return d
        val items = advanced.customItems?.filter { it.availableAt(ExplanationLevel.DIY) }
        return d.with(ExplanationLevel.DIY, LevelLayout(advanced.layout, items))
    }

    private fun encodeVoice(choice: VoiceChoice): String = when (choice) {
        VoiceChoice.MatchPersonality -> "match"
        is VoiceChoice.Profile -> "profile:${choice.id}"
        is VoiceChoice.Installed -> "installed:${choice.voiceName}"
    }

    /** A corrupt or unknown value falls back to the matching voice. */
    private fun decodeVoice(raw: String?): VoiceChoice = when {
        raw == null || raw == "match" -> VoiceChoice.MatchPersonality
        raw.startsWith("profile:") -> raw.removePrefix("profile:").takeIf { id -> AssistantProfiles.all.any { it.id == id } }
            ?.let { VoiceChoice.Profile(it) } ?: VoiceChoice.MatchPersonality
        raw.startsWith("installed:") -> raw.removePrefix("installed:").takeIf { it.isNotBlank() }
            ?.let { VoiceChoice.Installed(it) } ?: VoiceChoice.MatchPersonality
        else -> VoiceChoice.MatchPersonality
    }

    /**
     * Legacy settings (no schema marker) stored a free-text name and a raw voice name. A name that
     * differs from "Penelope" becomes a custom name on the Penelope profile; a saved voice becomes
     * an installed-voice choice. Migration is one-way: older app versions ignore the new keys.
     */
    private fun readPersonalization(store: KeyValueStore): Triple<String, String?, VoiceChoice> {
        // Only a missing marker means legacy; any stored version (even a newer one) already uses the new keys.
        if (store.getString(KEY_SCHEMA) != null) {
            val profile = AssistantProfiles.byId(store.getString(KEY_PROFILE)).id
            return Triple(profile, AssistantNamePolicy.sanitize(store.getString(KEY_CUSTOM_NAME)), decodeVoice(store.getString(KEY_VOICE_CHOICE)))
        }
        val legacyName = AssistantNamePolicy.sanitize(store.getString(KEY_LEGACY_NAME))
            ?.takeUnless { it.equals(AppSettings.DEFAULT_ASSISTANT_NAME, ignoreCase = true) }
        val voice = store.getString(KEY_LEGACY_VOICE)?.takeIf { it.isNotBlank() }?.let { VoiceChoice.Installed(it) }
            ?: VoiceChoice.MatchPersonality
        return Triple(AssistantProfiles.DEFAULT_ID, legacyName, voice)
    }

    fun read(store: KeyValueStore): AppSettings {
        val defaults = AppSettings()
        val (profileId, customName, voiceChoice) = readPersonalization(store)
        return AppSettings(
            activeVehicleId = store.getString(KEY_ACTIVE),
            assistantProfileId = profileId,
            customName = customName,
            voiceChoice = voiceChoice,
            demoDataEnabled = store.getBoolean(KEY_DEMO) ?: defaults.demoDataEnabled,
            lastAdapterAddress = store.getString(KEY_ADAPTER),
            speechRate = (store.getFloat(KEY_RATE) ?: defaults.speechRate).coerceIn(AppSettings.RATE_RANGE),
            speechPitch = (store.getFloat(KEY_PITCH) ?: defaults.speechPitch).coerceIn(AppSettings.PITCH_RANGE),
            ttsEngine = store.getString(KEY_ENGINE),
            continuousConversation = store.getBoolean(KEY_CONTINUOUS) ?: defaults.continuousConversation,
            explanationLevel = (storedLevel(store) ?: defaults.explanationLevel).toSelectable(),
            themeMode = ThemeMode.entries.firstOrNull { it.name == store.getString(KEY_THEME) } ?: defaults.themeMode,
            colorStyle = ColorStyle.entries.firstOrNull { it.name == store.getString(KEY_COLOR_STYLE) } ?: defaults.colorStyle,
            reduceMotion = store.getBoolean(KEY_REDUCE_MOTION) ?: defaults.reduceMotion,
            aiEnabled = store.getBoolean(KEY_AI) ?: defaults.aiEnabled,
            dashboard = readDashboard(store).let { if (storedLevel(store) == ExplanationLevel.ADVANCED) carryAdvancedLayout(it) else it },
        )
    }

    /** Unknown or removed shortcut names are skipped, so an app update can't break a saved layout. */
    private fun readDashboard(store: KeyValueStore): DashboardSettings {
        val byLevel = ExplanationLevel.entries.mapNotNull { level ->
            val layout = HomeLayout.entries.firstOrNull { it.name == store.getString(keyLayout(level)) } ?: return@mapNotNull null
            val items = store.getString(keyItems(level))?.split(',')
                ?.mapNotNull { name -> DashboardItem.entries.firstOrNull { it.name == name.trim() } }
            level to LevelLayout(layout, items)
        }.toMap()
        return DashboardSettings(byLevel)
    }

    private fun dashboardValues(d: DashboardSettings): Map<String, Any?> = ExplanationLevel.entries.flatMap { level ->
        val choice = d.byLevel[level]
        listOf(
            keyLayout(level) to choice?.layout?.name,
            keyItems(level) to choice?.customItems?.joinToString(",") { it.name },
        )
    }.toMap()

    fun write(store: KeyValueStore, s: AppSettings) = store.write(
        mapOf(
            KEY_ACTIVE to s.activeVehicleId,
            KEY_PROFILE to s.assistantProfileId,
            KEY_CUSTOM_NAME to s.customName,
            KEY_VOICE_CHOICE to encodeVoice(s.voiceChoice),
            KEY_SCHEMA to SCHEMA,
            KEY_LEGACY_NAME to null,
            KEY_LEGACY_VOICE to null,
            KEY_DEMO to s.demoDataEnabled,
            KEY_ADAPTER to s.lastAdapterAddress,
            KEY_RATE to s.speechRate,
            KEY_PITCH to s.speechPitch,
            KEY_ENGINE to s.ttsEngine,
            KEY_CONTINUOUS to s.continuousConversation,
            KEY_LEVEL to s.explanationLevel.name,
            KEY_THEME to s.themeMode.name,
            KEY_COLOR_STYLE to s.colorStyle.name,
            KEY_REDUCE_MOTION to s.reduceMotion,
            KEY_AI to s.aiEnabled,
        ) + dashboardValues(s.dashboard),
    )
}

class PersistentSettingsStore(private val store: KeyValueStore) : SettingsStore {
    private val state = MutableStateFlow(SettingsCodec.read(store)).also {
        // Rewrite legacy values once so the old keys are gone and the new ones are authoritative.
        if (SettingsCodec.needsMigration(store)) SettingsCodec.write(store, it.value)
        // Retired Advanced level: becomes DIY in place, once. Touches only the level (and DIY layout) keys.
        else if (SettingsCodec.needsLevelMigration(store)) SettingsCodec.migrateLevel(store, it.value)
    }
    override val settings: StateFlow<AppSettings> = state.asStateFlow()

    override fun update(transform: (AppSettings) -> AppSettings) {
        while (true) {
            val prev = state.value
            val next = transform(prev)
            if (state.compareAndSet(prev, next)) {
                SettingsCodec.write(store, next)
                return
            }
        }
    }
}

@Suppress("FunctionNaming") // Factory function named after the type it creates (Kotlin convention).
fun SharedPreferencesSettingsStore(context: Context): SettingsStore =
    PersistentSettingsStore(SharedPreferencesKeyValueStore(context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)))
