package com.aicarcompanion.app.ui.screenshots

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.ai.AiStatus
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.dashboard.DashboardKind
import com.aicarcompanion.app.dashboard.DashboardPlanner
import com.aicarcompanion.app.dashboard.DashboardSettings
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.ThemeMode
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdTransport
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DiagnosticTroubleCode
import com.aicarcompanion.app.domain.model.DtcSeverity
import com.aicarcompanion.app.domain.model.DtcStatus
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.RecordedTroubleCode
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.domain.model.TroubleCodeRead
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.ui.design.PenelopeBackground
import com.aicarcompanion.app.ui.home.HomeContent
import com.aicarcompanion.app.ui.home.HomeUiState
import com.aicarcompanion.app.ui.navigation.DriveScreen
import com.aicarcompanion.app.ui.navigation.FloatingNavBar
import com.aicarcompanion.app.ui.navigation.LevelNavigation
import com.aicarcompanion.app.ui.navigation.LiveDataContent
import com.aicarcompanion.app.ui.navigation.LiveDataStatus
import com.aicarcompanion.app.ui.navigation.MoreScreen
import com.aicarcompanion.app.ui.navigation.NavRoutes
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
 * Renders the Phase 1 screens to PNG files for visual review (build/screenshots/phase1). These are
 * Robolectric renders of the real composables, not device photos. Sample values are test fixtures.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w440dp-h2000dp-xhdpi")
class Phase1ScreenshotTest(private val shot: Shot) {

    data class Shot(
        val screen: String,
        val level: ExplanationLevel,
        val width: Int,
        val height: Int,
        val theme: ThemeMode = ThemeMode.DARK,
        val fontScale: Float = 1f,
    ) {
        val fileName: String get() =
            "${screen}_${level.name.lowercase()}_${width}x${height}_${theme.name.lowercase()}_font${(fontScale * 100).toInt()}.png"
        override fun toString() = fileName
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun shots(): List<Array<Any>> = buildList {
            for (level in ExplanationLevel.entries) {
                for ((w, h) in listOf(360 to 800, 412 to 915)) {
                    for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
                        for (font in listOf(1f, 2f)) add(Shot("home", level, w, h, theme, font))
                    }
                }
                add(Shot("home-full", level, 412, 1800))
                add(Shot("more", level, 412, 915))
            }
            add(Shot("drive", ExplanationLevel.ADVANCED, 412, 915))
            add(Shot("drive", ExplanationLevel.EVERYDAY, 412, 915, fontScale = 2f))
            add(Shot("livedata-live", ExplanationLevel.ADVANCED, 412, 915))
            add(Shot("livedata-disconnected", ExplanationLevel.ADVANCED, 412, 915))
            add(Shot("livedata-sample", ExplanationLevel.DIY, 360, 800, fontScale = 2f))
        }.map { arrayOf<Any>(it) }

        private val NOW: Instant = Instant.parse("2026-11-21T18:00:00Z")
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val car = Vehicle(
        id = "bmw", year = 2011, make = "BMW", model = "528i", trim = "F10",
        engine = EngineSpec(3.0, 6, EngineLayout.INLINE, engineCode = "N52B30"), odometerMiles = 98_000,
    )
    private val connected = ObdConnectionState.Connected(ObdTransport.BLUETOOTH_CLASSIC, "OBDII", "ISO 15765-4 CAN (11 bit, 500 kbaud)", "ELM327 v1.5")
    private val live = listOf(
        SensorReading("Engine speed", 709.0, "rpm", NOW, DataOrigin.OBD_BLUETOOTH, "010C"),
        SensorReading("Coolant temperature", 92.0, "°C", NOW, DataOrigin.OBD_BLUETOOTH, "0105"),
        SensorReading("Short term fuel trim bank 1", -0.8, "%", NOW, DataOrigin.OBD_BLUETOOTH, "0106"),
        SensorReading("Long term fuel trim bank 1", 3.1, "%", NOW, DataOrigin.OBD_BLUETOOTH, "0107"),
        SensorReading("Intake air temperature", 31.0, "°C", NOW, DataOrigin.OBD_BLUETOOTH, "010F"),
        SensorReading("Mass air flow", 5.5, "g/s", NOW, DataOrigin.OBD_BLUETOOTH, "0110"),
        SensorReading("Control module voltage", 14.2, "V", NOW, DataOrigin.OBD_BLUETOOTH, "0142"),
    )
    private val sample = live.map { it.copy(origin = DataOrigin.SAMPLE) }
    private val code = DiagnosticTroubleCode(
        "P0455", "EVAP system leak detected (large leak)", headline = "A large evaporative-system leak",
        status = DtcStatus.PENDING, severity = DtcSeverity.MODERATE, origin = DataOrigin.OBD_BLUETOOTH,
    )
    private val readAt = Instant.parse("2026-11-21T17:52:00Z")
    private val read = TroubleCodeRead(
        "r1", "bmw", readAt, DataOrigin.OBD_BLUETOOTH,
        listOf(RecordedTroubleCode(vehicleId = "bmw", code = "P0455", status = DtcStatus.PENDING, readAt = readAt, readSessionId = "r1", origin = DataOrigin.OBD_BLUETOOTH)),
    )
    private val ui = HomeUiState(vehicle = car, obdState = connected, readings = live, codes = listOf(code), lastRead = read, loaded = true)

    @Test fun render() {
        val s = shot
        compose.setContent {
            CarCompanionTheme(themeMode = s.theme, reduceMotion = true) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, s.fontScale)) {
                    Box(Modifier.requiredSize(s.width.dp, s.height.dp).testTag(ROOT)) {
                        PenelopeBackground(waves = false) { Screen(s) }
                    }
                }
            }
        }
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag(ROOT).captureToImage().asAndroidBitmap()
        val dir = File("build/screenshots/phase1").apply { mkdirs() }
        File(dir, s.fileName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Screen(s: Shot) {
        val tabs = LevelNavigation.tabs(s.level)
        val navSpace = PaddingValues(bottom = 104.dp)
        val selected = when (s.screen) {
            "drive" -> NavRoutes.DRIVE
            "more" -> NavRoutes.MORE
            "livedata-live", "livedata-disconnected", "livedata-sample" -> NavRoutes.LIVE_DATA
            else -> NavRoutes.HOME
        }
        Box(Modifier.requiredSize(s.width.dp, s.height.dp)) {
            when (s.screen) {
                "home", "home-full" -> HomeContent(
                    contentPadding = navSpace, voice = VoiceState.Idle, conversationActive = false, micLevel = 0f,
                    assistantName = "Penelope", greeting = "Good evening", ui = ui, photo = null,
                    onMic = {}, onPause = {}, onEnd = {}, onSuggestion = {}, onOpenAppSettings = {},
                    onOpenGarage = {}, onOpenAdapter = {}, onOpenSettings = {}, onOpenResults = {}, onAddVehicle = {},
                    onSelectVehicle = {}, onPhotoPicked = {}, aiStatus = AiStatus.Off,
                    items = DashboardPlanner.itemsFor(s.level, DashboardSettings()), kind = DashboardKind.forLevel(s.level), now = NOW,
                )
                "more" -> MoreScreen(s.level, "Penelope", navSpace) {}
                "drive" -> DriveScreen("Penelope", navSpace) {}
                "livedata-live" -> LiveDataContent(LiveDataStatus.of(true, live, connected, NOW), live, NOW, true, navSpace)
                "livedata-disconnected" -> LiveDataContent(LiveDataStatus.of(true, live, ObdConnectionState.Disconnected, NOW), live, NOW, true, navSpace)
                "livedata-sample" -> LiveDataContent(LiveDataStatus.of(true, sample, ObdConnectionState.Disconnected, NOW), sample, NOW, false, navSpace)
            }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
                FloatingNavBar(tabs, selected, plain = s.level == ExplanationLevel.ADVANCED) {}
            }
        }
    }
}

private const val ROOT = "screenshot_root"
