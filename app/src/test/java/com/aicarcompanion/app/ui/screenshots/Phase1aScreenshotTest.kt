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
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.ThemeMode
import com.aicarcompanion.app.ui.design.PenelopeBackground
import com.aicarcompanion.app.ui.settings.KnowledgeLevelPicker
import com.aicarcompanion.app.ui.theme.CarCompanionTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the Phase 1A two-level picker (the Settings "How much detail?" choice) to PNG in
 * build/screenshots/phase1a. Robolectric renders of the real composable, not device photos.
 * There is no separate onboarding level picker in the app; Settings is the only place a level is chosen.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h900dp-xhdpi")
class Phase1aScreenshotTest(private val shot: Shot) {

    data class Shot(val level: ExplanationLevel, val theme: ThemeMode, val fontScale: Float) {
        val fileName: String get() = "level-picker-${level.name.lowercase()}-selected_${theme.name.lowercase()}_font${(fontScale * 100).toInt()}.png"
        override fun toString() = fileName
        val height: Int get() = if (fontScale > 1f) 560 else 240
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun shots(): List<Array<Any>> = buildList {
            for (level in ExplanationLevel.selectable) for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (font in listOf(1f, 2f)) add(Shot(level, theme, font))
        }.map { arrayOf<Any>(it) }
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun render() {
        val s = shot
        compose.setContent {
            CarCompanionTheme(themeMode = s.theme, reduceMotion = true) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, s.fontScale)) {
                    Box(Modifier.requiredSize(412.dp, s.height.dp).testTag(ROOT)) {
                        PenelopeBackground(waves = false) {
                            Column(Modifier.padding(16.dp)) { KnowledgeLevelPicker(s.level, {}) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag(ROOT).captureToImage().asAndroidBitmap()
        val dir = File("build/screenshots/phase1a").apply { mkdirs() }
        File(dir, s.fileName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

private const val ROOT = "screenshot_root_phase1a"
