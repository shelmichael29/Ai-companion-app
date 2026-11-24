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
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.ThemeMode
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdTransport
import com.aicarcompanion.app.domain.livedata.DashboardLayout
import com.aicarcompanion.app.domain.livedata.GaugeSize
import com.aicarcompanion.app.domain.livedata.GaugeSpec
import com.aicarcompanion.app.domain.livedata.GaugeStyle
import com.aicarcompanion.app.domain.livedata.LiveSensorCatalog
import com.aicarcompanion.app.domain.livedata.SensorValueState
import com.aicarcompanion.app.ui.design.PenelopeBackground
import com.aicarcompanion.app.ui.livedata.LiveDataUiState
import com.aicarcompanion.app.ui.livedata.LiveDataWorkspaceContent
import com.aicarcompanion.app.ui.livedata.WorkspaceActions
import com.aicarcompanion.app.ui.livedata.WorkspaceTab
import com.aicarcompanion.app.ui.theme.CarCompanionTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/**
 * Renders the Phase 2 Live Data workspace to PNG files for visual review (build/screenshots/phase2).
 * Robolectric renders of the real composables with test-fixture values, not device photos and not
 * readings from a car.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h2000dp-xhdpi")
class Phase2ScreenshotTest(private val shot: Shot) {

    data class Shot(val screen: String, val level: ExplanationLevel, val fontScale: Float = 1f, val height: Int = 915) {
        val fileName: String get() = "livedata-${screen}_${level.name.lowercase()}_font${(fontScale * 100).toInt()}.png"
        override fun toString() = fileName
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun shots(): List<Array<Any>> = buildList {
            for (level in listOf(ExplanationLevel.DIY, ExplanationLevel.ADVANCED)) {
                GaugeStyle.entries.forEach { add(Shot("style-${it.name.lowercase()}", level)) }
                add(Shot("dashboard-mixed", level))
                add(Shot("dashboard-mixed", level, fontScale = 2f, height = 1800))
                add(Shot("edit", level, height = 1400))
                add(Shot("all-sensors", level, height = 1400))
                add(Shot("states", level, height = 1100))
                add(Shot("sample", level))
                add(Shot("disconnected", level, height = 800))
            }
        }.map { arrayOf<Any>(it) }

        private val NOW: Instant = Instant.parse("2026-11-21T18:00:00Z")
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val connected = ObdConnectionState.Connected(ObdTransport.BLUETOOTH_CLASSIC, "OBDII", "ISO 15765-4 CAN (11 bit, 500 kbaud)", "ELM327 v1.5")

    /** Fixture values inside each sensor's scale. */
    private fun fixture(pid: Int): Double = when (pid) {
        0x0C -> 2_150.0; 0x0D -> 72.0; 0x05 -> 92.0; 0x42 -> 14.1; 0x2F -> 63.0; 0x04 -> 34.0; 0x11 -> 18.0
        0x0F -> 31.0; 0x06 -> -0.8; 0x07 -> 3.1; 0x10 -> 5.5; 0x0B -> 38.0
        else -> 0.0
    }

    private val history = listOf(0x0C, 0x0D, 0x05, 0x42).associateWith { pid ->
        val s = LiveSensorCatalog.find(pid)!!
        (0 until 40).map { i -> (fixture(pid) + (s.max - s.min) * 0.04 * Math.sin(i / 4.0)).coerceIn(s.min, s.max) }
    }

    private fun live(pids: Collection<Int>): Map<Int, SensorValueState> = pids.associateWith { SensorValueState.Live(fixture(it), NOW) }

    private fun layout(vararg specs: GaugeSpec) = DashboardLayout("l", "bmw", "Daily", specs.toList())

    private fun uiFor(s: Shot): LiveDataUiState {
        val four = listOf(0x0C, 0x0D, 0x05, 0x42)
        fun base(l: DashboardLayout, states: Map<Int, SensorValueState>, tab: WorkspaceTab = WorkspaceTab.DASHBOARD, editing: Boolean = false, obd: ObdConnectionState = connected) =
            LiveDataUiState(true, listOf(l, DashboardLayout("l2", "bmw", "Track day")), l, tab, editing, states, history, obd)
        return when {
            s.screen.startsWith("style-") -> {
                val style = GaugeStyle.valueOf(s.screen.removePrefix("style-").uppercase())
                val size = if (style == GaugeStyle.ANALOG || style == GaugeStyle.LINE_GRAPH) GaugeSize.MEDIUM else GaugeSize.SMALL
                base(layout(*four.map { GaugeSpec(it, style, size) }.toTypedArray()), live(four))
            }
            s.screen == "dashboard-mixed" -> base(
                layout(
                    GaugeSpec(0x0C, GaugeStyle.ANALOG, GaugeSize.LARGE), GaugeSpec(0x0D, GaugeStyle.NUMERIC),
                    GaugeSpec(0x05, GaugeStyle.STATUS), GaugeSpec(0x42, GaugeStyle.LINE_GRAPH, GaugeSize.MEDIUM),
                    GaugeSpec(0x2F, GaugeStyle.DIGITAL), GaugeSpec(0x04, GaugeStyle.DIGITAL),
                ),
                live(listOf(0x0C, 0x0D, 0x05, 0x42, 0x2F, 0x04)),
            )
            s.screen == "edit" -> base(
                layout(GaugeSpec(0x0C, GaugeStyle.ANALOG, GaugeSize.MEDIUM), GaugeSpec(0x05), GaugeSpec(0x42, GaugeStyle.LINE_GRAPH, GaugeSize.MEDIUM)),
                live(listOf(0x0C, 0x05, 0x42)), editing = true,
            )
            s.screen == "all-sensors" -> {
                val states: Map<Int, SensorValueState> = LiveSensorCatalog.all.associate { sensor ->
                    sensor.pid to when (sensor.pid) {
                        0x06, 0x07, 0x08, 0x09 -> SensorValueState.Unsupported
                        0x10 -> SensorValueState.Stale(fixture(0x10), NOW.minusSeconds(14))
                        else -> SensorValueState.Live(fixture(sensor.pid), NOW)
                    }
                }
                base(layout(GaugeSpec(0x0C)), states, tab = WorkspaceTab.ALL_SENSORS)
            }
            s.screen == "states" -> base(
                layout(
                    GaugeSpec(0x0C, GaugeStyle.ANALOG), GaugeSpec(0x0D), GaugeSpec(0x05), GaugeSpec(0x42), GaugeSpec(0x2F),
                ),
                mapOf(
                    0x0C to SensorValueState.Unsupported,
                    0x0D to SensorValueState.TemporarilyUnavailable("No answer"),
                    0x05 to SensorValueState.Stale(88.0, NOW.minusSeconds(12)),
                    0x42 to SensorValueState.Paused("Paused while trouble codes are cleared"),
                    0x2F to SensorValueState.Disconnected,
                ),
            )
            s.screen == "sample" -> base(
                layout(*four.map { GaugeSpec(it, GaugeStyle.DIGITAL) }.toTypedArray()),
                four.associateWith { SensorValueState.Sample(fixture(it), NOW) }, obd = ObdConnectionState.Disconnected,
            )
            else -> base(layout(*four.map { GaugeSpec(it) }.toTypedArray()), four.associateWith { SensorValueState.Disconnected }, obd = ObdConnectionState.Disconnected)
        }
    }

    @Test fun render() {
        val s = shot
        compose.setContent {
            CarCompanionTheme(themeMode = ThemeMode.DARK, reduceMotion = true) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, s.fontScale)) {
                    Box(Modifier.requiredSize(412.dp, s.height.dp).testTag(ROOT)) {
                        PenelopeBackground(waves = false) {
                            LiveDataWorkspaceContent(uiFor(s), s.level, NOW, WorkspaceActions(), PaddingValues())
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag(ROOT).captureToImage().asAndroidBitmap()
        val dir = File("build/screenshots/phase2").apply { mkdirs() }
        File(dir, s.fileName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

private const val ROOT = "screenshot_root_p2"
