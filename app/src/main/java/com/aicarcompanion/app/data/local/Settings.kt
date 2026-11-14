package com.aicarcompanion.app.data.local

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** How much detail Penelope gives. Reasoning is identical across levels; only presentation changes. */
enum class ExplanationLevel(val label: String, val description: String) {
    EVERYDAY("Everyday driver", "Simple explanations and clear next steps. No car knowledge needed."),
    DIY("DIY enthusiast", "Adds things you can check yourself and optional sensor readings."),
    ADVANCED("Advanced / professional", "Technical terms, code definitions and all available live data."),
}

enum class ThemeMode(val label: String) { SYSTEM("Match phone"), LIGHT("Light"), DARK("Dark") }

/** User preferences. Small enough for SharedPreferences; no extra dependency needed. */
data class AppSettings(
    val activeVehicleId: String? = null,
    /** The assistant's spoken/display name. Users can rename Penelope in Settings. */
    val assistantName: String = DEFAULT_ASSISTANT_NAME,
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
    /** TTS voice name within the engine; null = engine default for the language. */
    val ttsVoice: String? = null,
    /** Keep listening after each reply (hands-free conversation). On by default. */
    val continuousConversation: Boolean = true,
    val explanationLevel: ExplanationLevel = ExplanationLevel.EVERYDAY,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Calmer visuals: static orb, no background motion. Android's "Remove animations" also applies. */
    val reduceMotion: Boolean = false,
) {
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
    override fun write(values: Map<String, Any?>) { map.putAll(values) }
}

class SharedPreferencesKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String) = prefs.getString(key, null)
    override fun getFloat(key: String) = if (prefs.contains(key)) prefs.getFloat(key, 0f) else null
    override fun getBoolean(key: String) = if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    override fun write(values: Map<String, Any?>) {
        val editor = prefs.edit()
        values.forEach { (k, v) ->
            when (v) {
                null -> editor.remove(k)
                is String -> editor.putString(k, v)
                is Float -> editor.putFloat(k, v)
                is Boolean -> editor.putBoolean(k, v)
                else -> error("Unsupported type for $k")
            }
        }
        editor.apply()
    }
}

/** Reads and writes [AppSettings], clamping values so a bad stored value can't distort the voice. */
object SettingsCodec {
    private const val KEY_ACTIVE = "active_vehicle_id"
    private const val KEY_NAME = "assistant_name"
    private const val KEY_DEMO = "demo_data_enabled"
    private const val KEY_ADAPTER = "last_adapter_address"
    private const val KEY_RATE = "speech_rate"
    private const val KEY_PITCH = "speech_pitch"
    private const val KEY_ENGINE = "tts_engine"
    private const val KEY_VOICE = "tts_voice"
    private const val KEY_CONTINUOUS = "continuous_conversation"
    private const val KEY_LEVEL = "explanation_level"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_REDUCE_MOTION = "reduce_motion"

    fun read(store: KeyValueStore): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            activeVehicleId = store.getString(KEY_ACTIVE),
            assistantName = store.getString(KEY_NAME)?.takeIf { it.isNotBlank() } ?: defaults.assistantName,
            demoDataEnabled = store.getBoolean(KEY_DEMO) ?: defaults.demoDataEnabled,
            lastAdapterAddress = store.getString(KEY_ADAPTER),
            speechRate = (store.getFloat(KEY_RATE) ?: defaults.speechRate).coerceIn(AppSettings.RATE_RANGE),
            speechPitch = (store.getFloat(KEY_PITCH) ?: defaults.speechPitch).coerceIn(AppSettings.PITCH_RANGE),
            ttsEngine = store.getString(KEY_ENGINE),
            ttsVoice = store.getString(KEY_VOICE),
            continuousConversation = store.getBoolean(KEY_CONTINUOUS) ?: defaults.continuousConversation,
            explanationLevel = ExplanationLevel.entries.firstOrNull { it.name == store.getString(KEY_LEVEL) }
                ?: defaults.explanationLevel,
            themeMode = ThemeMode.entries.firstOrNull { it.name == store.getString(KEY_THEME) } ?: defaults.themeMode,
            reduceMotion = store.getBoolean(KEY_REDUCE_MOTION) ?: defaults.reduceMotion,
        )
    }

    fun write(store: KeyValueStore, s: AppSettings) = store.write(
        mapOf(
            KEY_ACTIVE to s.activeVehicleId,
            KEY_NAME to s.assistantName,
            KEY_DEMO to s.demoDataEnabled,
            KEY_ADAPTER to s.lastAdapterAddress,
            KEY_RATE to s.speechRate,
            KEY_PITCH to s.speechPitch,
            KEY_ENGINE to s.ttsEngine,
            KEY_VOICE to s.ttsVoice,
            KEY_CONTINUOUS to s.continuousConversation,
            KEY_LEVEL to s.explanationLevel.name,
            KEY_THEME to s.themeMode.name,
            KEY_REDUCE_MOTION to s.reduceMotion,
        ),
    )
}

class PersistentSettingsStore(private val store: KeyValueStore) : SettingsStore {
    private val state = MutableStateFlow(SettingsCodec.read(store))
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

fun SharedPreferencesSettingsStore(context: Context): SettingsStore =
    PersistentSettingsStore(SharedPreferencesKeyValueStore(context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)))
