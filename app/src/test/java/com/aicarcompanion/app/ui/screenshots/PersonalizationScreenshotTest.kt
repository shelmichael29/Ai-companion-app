package com.aicarcompanion.app.ui.screenshots

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.assistant.persona.AssistantProfiles
import com.aicarcompanion.app.assistant.persona.VoiceChoice
import com.aicarcompanion.app.assistant.persona.VoiceGender
import com.aicarcompanion.app.assistant.voice.VoiceResolution
import com.aicarcompanion.app.data.local.AppSettings
import com.aicarcompanion.app.data.local.ThemeMode
import com.aicarcompanion.app.ui.design.PenelopeBackground
import com.aicarcompanion.app.ui.settings.PersonalizationActions
import com.aicarcompanion.app.ui.settings.PersonalizationContent
import com.aicarcompanion.app.ui.settings.PersonalizationState
import com.aicarcompanion.app.ui.theme.CarCompanionTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the personalization section to PNG (build/screenshots/personalization) for visual review.
 * Robolectric renders of the real composables with fixture voices. They are not device photos and say
 * nothing about which voices a real phone has.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h4200dp-xhdpi")
class PersonalizationScreenshotTest(private val shot: Shot) {

    data class Shot(val scenario: String, val theme: ThemeMode, val fontScale: Float) {
        val fileName: String get() = "personalization-${scenario}_${theme.name.lowercase()}_font${(fontScale * 100).toInt()}.png"
        override fun toString() = fileName
        val height: Int get() = if (fontScale > 1f) 3900 else 2000
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun shots(): List<Array<Any>> = buildList {
            for (scenario in listOf("default", "atlas-custom-name", "no-male-voice")) {
                for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (font in listOf(1f, 2f)) add(Shot(scenario, theme, font))
            }
        }.map { arrayOf<Any>(it) }

        private val fixtureResolutions: Map<String, VoiceResolution?> = AssistantProfiles.all.associate { p ->
            val n = p.voiceSlot % (if (p.voiceGender == VoiceGender.FEMALE) 6 else 3) + 1
            val word = if (p.voiceGender == VoiceGender.FEMALE) "Female" else "Male"
            p.id to VoiceResolution.Voice("fixture-${p.id}", "$word voice $n · works offline", p.voiceGender, shared = p.voiceGender == VoiceGender.MALE && p.voiceSlot >= 3, requiresNetwork = false)
        }
        private val noMaleResolutions: Map<String, VoiceResolution?> = fixtureResolutions.mapValues { (id, r) ->
            if (AssistantProfiles.byId(id).voiceGender == VoiceGender.MALE) VoiceResolution.FallbackDefault("No male voice is installed on this phone") else r
        }
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun stateFor(s: Shot): PersonalizationState = when (s.scenario) {
        "atlas-custom-name" -> PersonalizationState(
            AppSettings(assistantProfileId = "atlas", customName = "Jarvis", voiceChoice = VoiceChoice.Profile("luna"), speechRate = 1.2f, speechPitch = 0.9f),
            emptyList(), emptyList(), fixtureResolutions, nameError = null,
        )
        "no-male-voice" -> PersonalizationState(AppSettings(assistantProfileId = "oliver"), emptyList(), emptyList(), noMaleResolutions, nameError = null)
        else -> PersonalizationState(AppSettings(), emptyList(), emptyList(), fixtureResolutions, nameError = null)
    }

    @Test fun render() {
        val s = shot
        compose.setContent {
            CarCompanionTheme(themeMode = s.theme, reduceMotion = true) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, s.fontScale)) {
                    Box(Modifier.requiredSize(412.dp, s.height.dp).testTag(ROOT)) {
                        PenelopeBackground(waves = false) {
                            Column(Modifier.padding(16.dp)) { PersonalizationContent(stateFor(s), PersonalizationActions()) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag(ROOT).captureToImage().asAndroidBitmap()
        val dir = File("build/screenshots/personalization").apply { mkdirs() }
        File(dir, s.fileName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

private const val ROOT = "screenshot_root_personalization"
