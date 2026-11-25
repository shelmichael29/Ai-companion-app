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
import com.aicarcompanion.app.data.local.ThemeMode
import com.aicarcompanion.app.ui.design.PenelopeBackground
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.ui.adapter.ConnectionSettingsStatus
import com.aicarcompanion.app.ui.adapter.LiveReadingRows
import com.aicarcompanion.app.ui.adapter.TroubleshootingCard
import com.aicarcompanion.app.ui.components.SectionCard
import java.time.Instant
import com.aicarcompanion.app.ui.theme.CarCompanionTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the Phase 1B DIY pieces to PNG in build/screenshots/phase1b: the connection-settings status
 * header, the adapter live-readings card with sample values (clearly labelled, not from a car) and
 * the troubleshooting card with the custom-settings tip. Robolectric renders, not device photos.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h2400dp-xhdpi")
class Phase1bScreenshotTest(private val shot: Shot) {

    enum class Part(val slug: String, val h1: Int, val h2: Int) {
        STATUS("connection-settings-status", 140, 380),
        LIVE("live-readings-sample", 560, 1120),
        TIPS("troubleshooting-custom-tip", 200, 560),
    }

    data class Shot(val part: Part, val theme: ThemeMode, val fontScale: Float) {
        val fileName: String get() = "diy-${part.slug}_${theme.name.lowercase()}_font${(fontScale * 100).toInt()}.png"
        override fun toString() = fileName
        val height: Int get() = if (fontScale > 1f) part.h2 else part.h1
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun shots(): List<Array<Any>> = buildList {
            for (part in Part.entries) for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (font in listOf(1f, 2f)) add(Shot(part, theme, font))
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
                            Column(Modifier.padding(16.dp)) {
                                when (s.part) {
                                    Part.STATUS -> ConnectionSettingsStatus(appliesNow = true)
                                    Part.LIVE -> SectionCard("Live readings from your car") {
                                        Text("Sample values for this screenshot, not from a car.", style = MaterialTheme.typography.bodySmall)
                                        LiveReadingRows(sampleReadings(), everyday = false)
                                    }
                                    Part.TIPS -> TroubleshootingCard(
                                        listOf(
                                            "The adapter is fine but the car isn't answering. Turn the ignition fully on, then tap \"Check vehicle again\".",
                                            "Custom connection settings are on. If the car doesn't answer, open Advanced connection settings and tap Restore recommended.",
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag(ROOT).captureToImage().asAndroidBitmap()
        val dir = File("build/screenshots/phase1b").apply { mkdirs() }
        File(dir, s.fileName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

private const val ROOT = "screenshot_root_phase1b"

private fun sampleReadings(): List<SensorReading> = ObdPid.entries.map {
    SensorReading(it.label, if (it == ObdPid.ENGINE_RPM) 800.0 else 20.0, it.unit, Instant.EPOCH, DataOrigin.SAMPLE, it.command)
}
