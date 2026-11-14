package com.aicarcompanion.app.data.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** User preferences. Small enough for SharedPreferences; no extra dependency needed. */
data class AppSettings(
    val activeVehicleId: String? = null,
    /** The assistant's spoken/display name. Users can rename Penelope in Settings. */
    val assistantName: String = DEFAULT_ASSISTANT_NAME,
    /** When on, the dashboard and Penelope use labeled sample diagnostics. Off by default. */
    val demoDataEnabled: Boolean = false,
    /** Bluetooth address of the last OBD-II adapter the user connected to. */
    val lastAdapterAddress: String? = null,
    /** Text-to-speech rate multiplier (1.0 = normal). */
    val speechRate: Float = 1.0f,
) {
    companion object {
        const val DEFAULT_ASSISTANT_NAME = "Penelope"
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

class SharedPreferencesSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    override val settings: StateFlow<AppSettings> = state.asStateFlow()

    override fun update(transform: (AppSettings) -> AppSettings) {
        val new = state.updateAndGetCompat(transform)
        prefs.edit()
            .putString(KEY_ACTIVE, new.activeVehicleId)
            .putString(KEY_NAME, new.assistantName)
            .putBoolean(KEY_DEMO, new.demoDataEnabled)
            .putString(KEY_ADAPTER, new.lastAdapterAddress)
            .putFloat(KEY_RATE, new.speechRate)
            .apply()
    }

    private fun read() = AppSettings(
        activeVehicleId = prefs.getString(KEY_ACTIVE, null),
        assistantName = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }
            ?: AppSettings.DEFAULT_ASSISTANT_NAME,
        demoDataEnabled = prefs.getBoolean(KEY_DEMO, false),
        lastAdapterAddress = prefs.getString(KEY_ADAPTER, null),
        speechRate = prefs.getFloat(KEY_RATE, 1.0f),
    )

    private fun MutableStateFlow<AppSettings>.updateAndGetCompat(f: (AppSettings) -> AppSettings): AppSettings {
        while (true) {
            val prev = value
            val next = f(prev)
            if (compareAndSet(prev, next)) return next
        }
    }

    private companion object {
        const val KEY_ACTIVE = "active_vehicle_id"
        const val KEY_NAME = "assistant_name"
        const val KEY_DEMO = "demo_data_enabled"
        const val KEY_ADAPTER = "last_adapter_address"
        const val KEY_RATE = "speech_rate"
    }
}
