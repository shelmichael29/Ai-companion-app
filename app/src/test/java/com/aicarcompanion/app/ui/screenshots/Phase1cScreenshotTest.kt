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
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.ai.AiStatus
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.assistant.conversation.AssistantReply
import com.aicarcompanion.app.assistant.conversation.ConversationTurn
import com.aicarcompanion.app.assistant.conversation.ReplyBasis
import com.aicarcompanion.app.assistant.conversation.Speaker
import com.aicarcompanion.app.dashboard.DashboardKind
import com.aicarcompanion.app.dashboard.DashboardPlanner
import com.aicarcompanion.app.dashboard.DashboardSettings
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.ThemeMode
import com.aicarcompanion.app.diagnostics.bluetooth.ConnectFailure
import com.aicarcompanion.app.diagnostics.obd.ConnectPhase
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdTransport
import com.aicarcompanion.app.domain.livedata.DashboardLayout
import com.aicarcompanion.app.domain.livedata.GaugeSpec
import com.aicarcompanion.app.domain.livedata.SensorValueState
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DiagnosticTroubleCode
import com.aicarcompanion.app.domain.model.DtcSeverity
import com.aicarcompanion.app.domain.model.DtcStatus
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.MaintenanceRecord
import com.aicarcompanion.app.domain.model.RecordedTroubleCode
import com.aicarcompanion.app.domain.model.TroubleCodeRead
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.ui.design.PenelopeBackground
import com.aicarcompanion.app.ui.diy.DiyLiveInput
import com.aicarcompanion.app.ui.home.HomeContent
import com.aicarcompanion.app.ui.home.HomeUiState
import com.aicarcompanion.app.ui.livedata.LiveDataUiState
import com.aicarcompanion.app.ui.livedata.LiveDataWorkspaceContent
import com.aicarcompanion.app.ui.livedata.WorkspaceActions
import com.aicarcompanion.app.ui.navigation.FloatingNavBar
import com.aicarcompanion.app.ui.navigation.LevelNavigation
import com.aicarcompanion.app.ui.navigation.MoreScreen
import com.aicarcompanion.app.ui.navigation.NavRoutes
import com.aicarcompanion.app.ui.results.ClearCodesAvailability
import com.aicarcompanion.app.ui.results.ClearCodesContent
import com.aicarcompanion.app.ui.results.ClearCodesUiState
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
 * Renders the DIY Overview and its neighbours to PNG files for visual review (build/screenshots/phase1c).
 * These are Robolectric renders of the real composables, not device photos. Readings and codes are test fixtures.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w440dp-h2000dp-xhdpi")
class Phase1cScreenshotTest(private val shot: Shot) {

    data class Shot(
        val screen: String,
        val width: Int,
        val height: Int,
        val theme: ThemeMode = ThemeMode.DARK,
        val fontScale: Float = 1f,
        /** Pixel density for the "S26 Ultra-like" render; null keeps the test device's density. */
        val density: Float? = null,
    ) {
        val fileName: String get() =
            "${screen}_${width}x${height}_${theme.name.lowercase()}_font${(fontScale * 100).toInt()}${if (density != null) "_d${density}" else ""}.png"
        override fun toString() = fileName
    }

    companion object {
        private val full = listOf("overview-ecu", "overview-disconnected", "overview-assistant-expanded")
        private val other = listOf("overview-sample", "overview-bt-only", "overview-adapter-ready", "overview-stale", "overview-error", "livedata-tab", "more-diy")

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun shots(): List<Array<Any>> = buildList {
            for (screen in full) for ((w, h) in listOf(360 to 800, 412 to 915)) {
                for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) for (font in listOf(1f, 2f)) add(Shot(screen, w, h, theme, font))
            }
            for (screen in other) {
                add(Shot(screen, 412, 915, ThemeMode.DARK, 1f))
                add(Shot(screen, 412, 915, ThemeMode.LIGHT, 1f))
                add(Shot(screen, 360, 800, ThemeMode.DARK, 2f))
            }
            add(Shot("overview-ecu", 412, 1800))
            add(Shot("overview-ecu", 412, 915, density = 3.5f))
        }.map { arrayOf<Any>(it) }

        private val NOW: Instant = Instant.parse("2026-11-26T18:00:00Z")
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val car = Vehicle(
        id = "bmw", year = 2011, make = "BMW", model = "528i", trim = "F10",
        engine = EngineSpec(3.0, 6, EngineLayout.INLINE, engineCode = "N52B30"), odometerMiles = 98_000,
    )
    private val bt = ObdTransport.BLUETOOTH_CLASSIC
    private val ecu = ObdConnectionState.Connected(bt, "OBDII", "ISO 15765-4 CAN (11 bit, 500 kbaud)", "ELM327 v1.5")
    private val readAt = Instant.parse("2026-11-26T17:52:00Z")
    private val read = TroubleCodeRead(
        "r1", "bmw", readAt, DataOrigin.OBD_BLUETOOTH,
        listOf(RecordedTroubleCode(vehicleId = "bmw", code = "P0455", status = DtcStatus.PENDING, readAt = readAt, readSessionId = "r1", origin = DataOrigin.OBD_BLUETOOTH)),
    )
    private fun code(origin: DataOrigin = DataOrigin.OBD_BLUETOOTH) = DiagnosticTroubleCode(
        "P0455", "EVAP system leak detected (large leak)", headline = "A large evaporative-system leak",
        status = DtcStatus.PENDING, severity = DtcSeverity.MODERATE, origin = origin,
    )
    private val service = MaintenanceRecord(
        "m1", "bmw", java.time.LocalDate.of(2026, 9, 14), "Oil and filter change", odometerMiles = 96_400, origin = DataOrigin.USER_ENTERED,
    )
    private val ui = HomeUiState(vehicle = car, vehicles = listOf(car), obdState = ecu, codes = listOf(code()), lastRead = read, loaded = true, lastService = service)
    private val gauges = listOf(0x0C, 0x05, 0x42, 0x0D).map { GaugeSpec(it) }
    private fun states(make: (Int) -> SensorValueState) = gauges.associate { it.pid to make(it.pid) }
    private fun value(pid: Int) = when (pid) { 0x0C -> 709.0; 0x05 -> 92.0; 0x42 -> 14.2; else -> 0.0 }
    private val turns = listOf(
        ConversationTurn(1, Speaker.USER, "Why is the check engine light on?", NOW),
        ConversationTurn(2, Speaker.ASSISTANT, "P0455 means the evaporative system found a large leak. A loose or worn fuel cap is the most common cause.", NOW),
        ConversationTurn(3, Speaker.USER, "Can I still drive?", NOW),
    )

    @Test fun render() {
        val s = shot
        compose.setContent {
            CarCompanionTheme(themeMode = s.theme, reduceMotion = true) {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(s.density ?: base.density, s.fontScale)) {
                    Box(Modifier.requiredSize(s.width.dp, s.height.dp).testTag(ROOT)) {
                        PenelopeBackground(waves = false) { Screen(s) }
                    }
                }
            }
        }
        if (s.screen == "overview-assistant-expanded") compose.onNodeWithContentDescription("Conversation details").performClick()
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag(ROOT).captureToImage().asAndroidBitmap()
        val dir = File("build/screenshots/phase1c").apply { mkdirs() }
        File(dir, s.fileName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Screen(s: Shot) {
        val diy = ExplanationLevel.DIY
        val navSpace = PaddingValues(bottom = 104.dp)
        val selected = when (s.screen) {
            "more-diy" -> NavRoutes.MORE
            "livedata-tab" -> NavRoutes.LIVE_DATA
            else -> NavRoutes.HOME
        }
        Box(Modifier.requiredSize(s.width.dp, s.height.dp)) {
            when (s.screen) {
                "livedata-tab" -> LiveDataWorkspaceContent(
                    LiveDataUiState(
                        true, listOf(DashboardLayout("l", "bmw", "Daily", gauges)), DashboardLayout("l", "bmw", "Daily", gauges),
                        states = states { SensorValueState.Live(value(it), NOW) }, obdState = ecu,
                    ),
                    diy, NOW, WorkspaceActions(), navSpace,
                )
                "more-diy" -> MoreScreen(diy, "Penelope", navSpace) {}
                else -> Overview(s, navSpace)
            }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
                FloatingNavBar(LevelNavigation.tabs(diy), selected, plain = false) {}
            }
        }
    }

    @Composable
    private fun Overview(s: Shot, navSpace: PaddingValues) {
        val (home, live, voice) = when (s.screen) {
            "overview-sample" -> Triple(
                ui.copy(obdState = ObdConnectionState.Disconnected, codes = listOf(code(DataOrigin.SAMPLE)), lastRead = read.copy(origin = DataOrigin.SAMPLE)),
                states { SensorValueState.Sample(value(it), NOW) }, VoiceState.Idle,
            )
            "overview-disconnected" -> Triple(
                ui.copy(obdState = ObdConnectionState.Disconnected, codes = emptyList(), lastRead = null, lastService = null),
                states { SensorValueState.Disconnected }, VoiceState.Idle,
            )
            "overview-bt-only" -> Triple(
                ui.copy(obdState = ObdConnectionState.Connecting(bt, "OBDII", ConnectPhase.LINK_OPEN), codes = emptyList(), lastRead = null),
                states { SensorValueState.Disconnected }, VoiceState.Idle,
            )
            "overview-adapter-ready" -> Triple(
                ui.copy(obdState = ObdConnectionState.Connected(bt, "OBDII", vehicleProtocol = null, adapterVersion = "ELM327 v1.5")),
                states { SensorValueState.TemporarilyUnavailable("Vehicle not responding. Is the ignition on?") }, VoiceState.Idle,
            )
            "overview-stale" -> Triple(ui, states { SensorValueState.Stale(value(it), NOW.minusSeconds(60)) }, VoiceState.Idle)
            "overview-error" -> Triple(
                ui.copy(obdState = ObdConnectionState.Failed("Your adapter didn't answer in time.", ConnectFailure.TIMEOUT)),
                states { SensorValueState.Disconnected }, VoiceState.Error("I need the microphone to hear you.", needsPermission = true),
            )
            "overview-assistant-expanded" -> Triple(
                ui, states { SensorValueState.Live(value(it), NOW) },
                VoiceState.Speaking(AssistantReply("It is fine to drive for now. Tighten the fuel cap and I will re-check.", ReplyBasis.CONVERSATION)),
            )
            else -> Triple(ui, states { SensorValueState.Live(value(it), NOW) }, VoiceState.Idle)
        }
        HomeContent(
            contentPadding = navSpace, voice = voice, conversationActive = voice is VoiceState.Speaking, micLevel = 0f,
            assistantName = "Penelope", greeting = "", ui = home, photo = null,
            onMic = {}, onPause = {}, onEnd = {}, onSuggestion = {}, onOpenAppSettings = {},
            onOpenGarage = {}, onOpenAdapter = {}, onOpenSettings = {}, onOpenResults = {}, onAddVehicle = {},
            onSelectVehicle = {}, onPhotoPicked = {}, aiStatus = AiStatus.Off,
            items = DashboardPlanner.itemsFor(ExplanationLevel.DIY, DashboardSettings()), kind = DashboardKind.WORKSHOP, now = NOW,
            clearCodes = { ClearCodesContent(ClearCodesUiState(ClearCodesAvailability(true, home.lastRead != null, null)), {}, {}, {}, {}) },
            diyLive = DiyLiveInput(true, gauges, live), turns = if (s.screen == "overview-assistant-expanded") turns else emptyList(),
            profileName = "Penelope Classic",
        )
    }
}

private const val ROOT = "screenshot_root_p1c"
