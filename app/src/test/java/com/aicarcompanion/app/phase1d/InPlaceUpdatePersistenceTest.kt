package com.aicarcompanion.app.phase1d

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aicarcompanion.app.BuildConfig
import com.aicarcompanion.app.assistant.persona.VoiceChoice
import com.aicarcompanion.app.dashboard.DashboardItem
import com.aicarcompanion.app.dashboard.HomeLayout
import com.aicarcompanion.app.dashboard.LevelLayout
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.SharedPreferencesKeyValueStore
import com.aicarcompanion.app.data.local.SharedPreferencesSettingsStore
import com.aicarcompanion.app.data.local.ThemeMode
import com.aicarcompanion.app.data.local.VoiceSource
import com.aicarcompanion.app.diagnostics.obd.ObdConfigStore
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionConfig
import com.aicarcompanion.app.diagnostics.obd.ObdProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1D: an in-place APK update on the owner's phone keeps what the user chose. The test writes the
 * real SharedPreferences files ("app_settings", "obd_settings") the way an earlier build left them, then
 * opens them with the current code, "restarts" (opens them again) and checks nothing was lost or changed.
 * Only the stored files are simulated; installing over an older APK still needs the phone
 * (docs/DEVICE_TESTING.md, section 0h).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class InPlaceUpdatePersistenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val appPrefs get() = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val obdPrefs get() = context.getSharedPreferences("obd_settings", Context.MODE_PRIVATE)

    @Before fun clear() {
        appPrefs.edit().clear().commit()
        obdPrefs.edit().clear().commit()
    }

    /** What v0.8.0 (build 9, before the two-level consolidation) wrote for a user on Advanced with a custom setup. */
    private fun writeV080AdvancedUser() {
        appPrefs.edit()
            .putString("settings_schema", "2")
            .putString("assistant_profile", "ace")
            .putString("custom_assistant_name", "Nova")
            .putString("voice_choice", "installed:en-us-x-iom-local") // the old voice dropdown's choice
            .putFloat("speech_rate", 1.2f)
            .putFloat("speech_pitch", 0.9f)
            .putString("tts_engine", "com.example.tts")
            .putBoolean("continuous_conversation", false)
            .putString("explanation_level", "ADVANCED")
            .putString("theme_mode", "LIGHT")
            .putBoolean("ai_enabled", false)
            .putString("home_layout_advanced", "CUSTOMIZABLE")
            .putString("home_items_advanced", "ADAPTER,CONNECTION_SETTINGS,HISTORY")
            .putString("home_layout_everyday", "CUSTOMIZABLE")
            .putString("home_items_everyday", "MY_VEHICLE,CAR_HEALTH,SETTINGS")
            .commit()
    }

    @Test fun `an Advanced user from v0_8_0 keeps personality, voice, speech and layouts and lands on DIY`() {
        writeV080AdvancedUser()
        val first = SharedPreferencesSettingsStore(context).settings.value

        assertEquals(ExplanationLevel.DIY, first.explanationLevel)
        assertEquals("ace", first.assistantProfileId)
        assertEquals("Nova", first.customName)
        assertEquals(VoiceChoice.MatchPersonality, first.voiceChoice) // the voice follows the profile
        assertEquals(VoiceSource.OFFLINE, first.voiceSource) // no stored source before #77: the default
        assertEquals(1.2f, first.speechRate)
        assertEquals(0.9f, first.speechPitch)
        assertEquals("com.example.tts", first.ttsEngine)
        assertEquals(false, first.continuousConversation)
        assertEquals(ThemeMode.LIGHT, first.themeMode)
        assertEquals(false, first.aiEnabled)
        assertEquals(
            LevelLayout(HomeLayout.CUSTOMIZABLE, listOf(DashboardItem.ADAPTER, DashboardItem.CONNECTION_SETTINGS, DashboardItem.HISTORY)),
            first.dashboard.forLevel(ExplanationLevel.DIY),
        )
        assertEquals(
            LevelLayout(HomeLayout.CUSTOMIZABLE, listOf(DashboardItem.MY_VEHICLE, DashboardItem.CAR_HEALTH, DashboardItem.SETTINGS)),
            first.dashboard.forLevel(ExplanationLevel.EVERYDAY),
        )
        // Migrated on disk, once.
        assertEquals("DIY", appPrefs.getString("explanation_level", null))
        assertEquals("match", appPrefs.getString("voice_choice", null))

        // Restart, twice: exactly the same settings, and the stored file no longer changes.
        val afterMigration = appPrefs.all.toMap()
        assertEquals(first, SharedPreferencesSettingsStore(context).settings.value)
        assertEquals(first, SharedPreferencesSettingsStore(context).settings.value)
        assertEquals(afterMigration, appPrefs.all.toMap())
    }

    @Test fun `a change made after the update survives a restart`() {
        writeV080AdvancedUser()
        SharedPreferencesSettingsStore(context).update {
            it.copy(explanationLevel = ExplanationLevel.EVERYDAY, assistantProfileId = "penelope", voiceSource = VoiceSource.ONLINE, speechRate = 0.8f)
        }
        val reopened = SharedPreferencesSettingsStore(context).settings.value
        assertEquals(ExplanationLevel.EVERYDAY, reopened.explanationLevel)
        assertEquals("penelope", reopened.assistantProfileId)
        assertEquals("Nova", reopened.customName)
        assertEquals(VoiceSource.ONLINE, reopened.voiceSource)
        assertEquals(0.8f, reopened.speechRate)
        // Switching back to DIY finds its layout untouched.
        assertEquals(HomeLayout.CUSTOMIZABLE, reopened.dashboard.forLevel(ExplanationLevel.DIY).layout)
    }

    @Test fun `custom connection settings survive an update and a restart and apply only at DIY`() {
        val custom = ObdConnectionConfig.RECOMMENDED.copy(
            protocol = ObdProtocol.entries.first { it != ObdProtocol.AUTO }, responseTimeoutMillis = 3_000, retries = 2,
            pollIntervalMillis = 500, communicationLog = true,
        )
        ObdConfigStore(SharedPreferencesKeyValueStore(obdPrefs)).update { custom }
        val reopened = ObdConfigStore(SharedPreferencesKeyValueStore(obdPrefs)).config.value
        assertEquals(custom, reopened)
        assertEquals(custom, ObdConnectionConfig.effectiveFor(ExplanationLevel.DIY, reopened))
        assertEquals(ObdConnectionConfig.RECOMMENDED, ObdConnectionConfig.effectiveFor(ExplanationLevel.EVERYDAY, reopened))
    }

    @Test fun `the update keeps the application id and moves the version code forward`() {
        // Changing the application id would install a second app instead of updating; a lower version code is refused.
        assertEquals("com.aicarcompanion.app", BuildConfig.APPLICATION_ID)
        assertTrue("versionCode ${BuildConfig.VERSION_CODE} must be above build 14 (v0.12.0)", BuildConfig.VERSION_CODE > LAST_MERGED_VERSION_CODE)
    }

    private companion object {
        /** v0.11.1, the build on main before Phase 1D. */
        const val LAST_MERGED_VERSION_CODE = 14
    }
}
