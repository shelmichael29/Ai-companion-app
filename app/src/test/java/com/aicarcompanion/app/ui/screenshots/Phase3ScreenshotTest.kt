package com.aicarcompanion.app.ui.screenshots

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.ThemeMode
import com.aicarcompanion.app.ui.design.PenelopeBackground
import com.aicarcompanion.app.ui.drive.DriveActions
import com.aicarcompanion.app.ui.drive.DriveContent
import com.aicarcompanion.app.ui.drive.DriveFixtures
import com.aicarcompanion.app.ui.drive.DriveUiState
import com.aicarcompanion.app.ui.theme.CarCompanionTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the Drive screen (Phase 3) to PNG files for visual review (build/screenshots/phase3).
 * Robolectric renders of the real composables with made-up fixture names and titles. They are not
 * device photos, and nothing here proves media, message or call behavior on a phone.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h2000dp-xhdpi")
class Phase3ScreenshotTest(private val shot: Shot) {

    data class Shot(val scenario: String, val level: ExplanationLevel, val theme: ThemeMode, val fontScale: Float) {
        val fileName: String get() = "drive-${scenario}_${level.name.lowercase()}_${theme.name.lowercase()}_font${(fontScale * 100).toInt()}.png"
        override fun toString() = fileName
        val height: Int get() = if (fontScale > 1f) 3400 else 1700
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun shots(): List<Array<Any>> = buildList {
            for (level in ExplanationLevel.entries) for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                for (font in listOf(1f, 2f)) {
                    add(Shot("playing-session", level, theme, font))
                    add(Shot("needs-access-keys-only", level, theme, font))
                }
                add(Shot("confirmation-pending", level, theme, 1f))
                add(Shot("unread-messages", level, theme, 1f))
            }
        }.map { arrayOf<Any>(it) }
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun uiFor(s: Shot): DriveUiState = when (s.scenario) {
        "playing-session" -> DriveFixtures.playing
        "needs-access-keys-only" -> DriveFixtures.needsAccess
        "confirmation-pending" -> DriveFixtures.confirmReply
        else -> DriveFixtures.unread
    }

    @Test fun render() {
        val s = shot
        compose.setContent {
            CarCompanionTheme(themeMode = s.theme, reduceMotion = true) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, s.fontScale)) {
                    Box(Modifier.requiredSize(412.dp, s.height.dp).testTag(ROOT)) {
                        PenelopeBackground(waves = false) {
                            DriveContent(uiFor(s), s.level, "Penelope", DriveActions(), PaddingValues(bottom = 24.dp))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag(ROOT).captureToImage().asAndroidBitmap()
        val dir = File("build/screenshots/phase3").apply { mkdirs() }
        File(dir, s.fileName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

private const val ROOT = "screenshot_root_p3"
