package com.aicarcompanion.app.phase1d

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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.ai.AiStatus
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.assistant.conversation.ConversationTurn
import com.aicarcompanion.app.assistant.conversation.Speaker
import com.aicarcompanion.app.dashboard.DashboardKind
import com.aicarcompanion.app.dashboard.DashboardPlanner
import com.aicarcompanion.app.dashboard.DashboardSettings
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.ThemeMode
import com.aicarcompanion.app.diagnostics.bluetooth.ConnectFailure
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdTransport
import com.aicarcompanion.app.domain.livedata.DashboardLayout
import com.aicarcompanion.app.domain.livedata.GaugeSpec
import com.aicarcompanion.app.domain.livedata.GaugeStyle
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
import com.aicarcompanion.app.ui.assistant.AssistantContent
import com.aicarcompanion.app.ui.design.PenelopeBackground
import com.aicarcompanion.app.ui.diy.DiyLiveInput
import com.aicarcompanion.app.ui.drive.DriveActions
import com.aicarcompanion.app.ui.drive.DriveContent
import com.aicarcompanion.app.ui.drive.DriveFixtures
import com.aicarcompanion.app.ui.home.HomeContent
import com.aicarcompanion.app.ui.home.HomeUiState
import com.aicarcompanion.app.ui.livedata.LiveDataUiState
import com.aicarcompanion.app.ui.livedata.LiveDataWorkspaceContent
import com.aicarcompanion.app.ui.livedata.WorkspaceActions
import com.aicarcompanion.app.ui.livedata.WorkspaceTab
import com.aicarcompanion.app.ui.navigation.FloatingNavBar
import com.aicarcompanion.app.ui.navigation.LevelNavigation
import com.aicarcompanion.app.ui.navigation.MoreScreen
import com.aicarcompanion.app.ui.navigation.NavRoutes
import com.aicarcompanion.app.ui.results.ClearCodesAvailability
import com.aicarcompanion.app.ui.results.ClearCodesContent
import com.aicarcompanion.app.ui.results.ClearCodesUiState
import com.aicarcompanion.app.ui.theme.CarCompanionTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/**
 * Phase 1D visual QA: renders the main Everyday and DIY screens in the configurations the earlier
 * screenshot tests did not cover (150 % font, a small 320 dp display, landscape, an S26 Ultra-like
 * density, loading / empty / error / disconnected states) and audits each render:
 * - every tappable element has a TalkBack label and a 48 × 48 dp touch area;
 * - nothing tappable or readable starts or ends off the side of the screen (horizontal scrollers excepted);
 * - no two unrelated tappable elements overlap.
 * Clipped or overlapping text is judged by looking at the PNGs (Robolectric's text layout reports
 * overflow for text that renders in full, so it is not asserted here).
 *
 * PNGs go to build/screenshots/phase1d. These are Robolectric renders of the real composables with
 * fixture data, not device captures; TalkBack itself still needs the phone.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w960dp-h2000dp-xhdpi")
class Phase1dVisualQaTest(private val shot: Shot) {

    data class Shot(
        val screen: String,
        val config: String,
        val width: Int,
        val height: Int,
        val theme: ThemeMode,
        val fontScale: Float,
        val density: Float? = null,
    ) {
        val fileName: String get() = "${screen}_${config}_${width}x${height}_${theme.name.lowercase()}_font${(fontScale * 100).toInt()}.png"
        override fun toString() = fileName
    }

    companion object {
        private val configs = listOf(
            Shot("", "font150", 412, 915, ThemeMode.DARK, 1.5f),
            Shot("", "font150", 412, 915, ThemeMode.LIGHT, 1.5f),
            Shot("", "font200", 412, 915, ThemeMode.LIGHT, 2f),
            Shot("", "compact", 360, 800, ThemeMode.DARK, 2f),
            Shot("", "small", 320, 640, ThemeMode.DARK, 1f),
            Shot("", "small", 320, 640, ThemeMode.LIGHT, 2f),
            Shot("", "landscape", 891, 412, ThemeMode.DARK, 1f),
            Shot("", "landscape", 891, 412, ThemeMode.LIGHT, 1.5f),
            // Galaxy S26 Ultra-like: 1440 px wide at about 3.5 px per dp gives a 412 x 891 dp window.
            Shot("", "s26ultra", 412, 891, ThemeMode.DARK, 1f, density = 3.5f),
            Shot("", "s26ultra", 412, 891, ThemeMode.LIGHT, 1.3f, density = 3.5f),
        )
        private val screens = listOf(
            "everyday-home", "everyday-no-vehicle", "everyday-loading", "everyday-error",
            "diy-overview", "diy-no-vehicle", "diy-disconnected",
            "diy-livedata", "diy-livedata-all", "diy-livedata-edit",
            "more-everyday", "more-diy", "drive-everyday", "drive-diy", "chat", "clear-codes",
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun shots(): List<Array<Any>> = screens.flatMap { s -> configs.map { it.copy(screen = s) } }.map { arrayOf<Any>(it) }

        private val NOW: Instant = Instant.parse("2026-11-26T18:00:00Z")
        private const val ROOT = "screenshot_root_p1d"
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
    private val code = DiagnosticTroubleCode(
        "P0455", "EVAP system leak detected (large leak)", headline = "A large evaporative-system leak",
        status = DtcStatus.PENDING, severity = DtcSeverity.MODERATE, origin = DataOrigin.OBD_BLUETOOTH,
    )
    private val service = MaintenanceRecord(
        "m1", "bmw", java.time.LocalDate.of(2026, 9, 14), "Oil and filter change", odometerMiles = 96_400, origin = DataOrigin.USER_ENTERED,
    )
    private val ui = HomeUiState(vehicle = car, vehicles = listOf(car), obdState = ecu, codes = listOf(code), lastRead = read, loaded = true, lastService = service)
    private val gauges = listOf(
        GaugeSpec(0x0C, GaugeStyle.ANALOG), GaugeSpec(0x05), GaugeSpec(0x42, GaugeStyle.LINE_GRAPH), GaugeSpec(0x0D, GaugeStyle.NUMERIC),
    )
    private val layout = DashboardLayout("l", "bmw", "Daily", gauges)
    private fun value(pid: Int) = when (pid) { 0x0C -> 709.0; 0x05 -> 92.0; 0x42 -> 14.2; else -> 0.0 }
    private val live = gauges.associate { it.pid to SensorValueState.Live(value(it.pid), NOW) as SensorValueState }
    private val turns = listOf(
        ConversationTurn(1, Speaker.USER, "Why is the check engine light on?", NOW),
        ConversationTurn(2, Speaker.ASSISTANT, "P0455 means the evaporative system found a large leak. A loose or worn fuel cap is the most common cause.", NOW),
    )

    @Test fun renderAndAudit() {
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
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag(ROOT).captureToImage().asAndroidBitmap()
        val dir = File("build/screenshots/phase1d").apply { mkdirs() }
        File(dir, s.fileName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        audit(s)
    }

    private fun level(s: Shot) = if (s.screen.startsWith("everyday") || s.screen.endsWith("everyday")) ExplanationLevel.EVERYDAY else ExplanationLevel.DIY

    @Composable
    private fun Screen(s: Shot) {
        val level = level(s)
        val navSpace = PaddingValues(bottom = 104.dp)
        val selected = when {
            s.screen.startsWith("more") -> NavRoutes.MORE
            s.screen.startsWith("drive") -> NavRoutes.DRIVE
            s.screen.startsWith("diy-livedata") -> NavRoutes.LIVE_DATA
            s.screen == "chat" || s.screen == "clear-codes" -> null
            else -> NavRoutes.HOME
        }
        Box(Modifier.requiredSize(s.width.dp, s.height.dp)) {
            when (s.screen) {
                "everyday-home" -> Home(ExplanationLevel.EVERYDAY, ui, VoiceState.Idle, navSpace)
                "everyday-no-vehicle" -> Home(ExplanationLevel.EVERYDAY, HomeUiState(loaded = true), VoiceState.Idle, navSpace)
                "everyday-loading" -> Home(ExplanationLevel.EVERYDAY, HomeUiState(), VoiceState.Idle, navSpace)
                "everyday-error" -> Home(
                    ExplanationLevel.EVERYDAY,
                    ui.copy(obdState = ObdConnectionState.Failed("Your adapter didn't answer in time.", ConnectFailure.TIMEOUT)),
                    VoiceState.Error("I need the microphone to hear you.", needsPermission = true), navSpace,
                )
                "diy-overview" -> Home(ExplanationLevel.DIY, ui, VoiceState.Idle, navSpace, DiyLiveInput(true, gauges, live))
                "diy-no-vehicle" -> Home(ExplanationLevel.DIY, HomeUiState(loaded = true), VoiceState.Idle, navSpace, DiyLiveInput(false, emptyList(), emptyMap()))
                "diy-disconnected" -> Home(
                    ExplanationLevel.DIY, ui.copy(obdState = ObdConnectionState.Disconnected, codes = emptyList(), lastRead = null),
                    VoiceState.Idle, navSpace, DiyLiveInput(true, gauges, gauges.associate { it.pid to SensorValueState.Disconnected }),
                )
                "diy-livedata", "diy-livedata-all", "diy-livedata-edit" -> LiveDataWorkspaceContent(
                    LiveDataUiState(
                        true, listOf(layout, DashboardLayout("l2", "bmw", "Track", emptyList())), layout,
                        tab = if (s.screen == "diy-livedata-all") WorkspaceTab.ALL_SENSORS else WorkspaceTab.DASHBOARD,
                        editing = s.screen == "diy-livedata-edit", states = live, obdState = ecu,
                    ),
                    ExplanationLevel.DIY, NOW, WorkspaceActions(), navSpace,
                )
                "more-everyday", "more-diy" -> MoreScreen(level, "Penelope", navSpace) {}
                "drive-everyday", "drive-diy" -> DriveContent(DriveFixtures.playing, level, "Penelope", DriveActions(), navSpace)
                "chat" -> AssistantContent(
                    contentPadding = PaddingValues(bottom = 96.dp), name = "Penelope", state = VoiceState.Idle, turns = turns,
                    conversationActive = true, micLevel = 0f, continuous = true, aiStatus = AiStatus.Off,
                    micAction = {}, onPause = {}, onEnd = {}, onSubmit = {}, onClear = {}, onBackHome = {}, onOpenAppSettings = {},
                )
                "clear-codes" -> ClearCodesContent(ClearCodesUiState(ClearCodesAvailability(true, true, null)), {}, {}, {}, {})
            }
            if (selected != null) {
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).testTag(NAV)) {
                    FloatingNavBar(LevelNavigation.tabs(level), selected, plain = false) {}
                }
            }
        }
    }

    @Composable
    private fun Home(level: ExplanationLevel, home: HomeUiState, voice: VoiceState, navSpace: PaddingValues, diyLive: DiyLiveInput? = null) {
        HomeContent(
            contentPadding = navSpace, voice = voice, conversationActive = false, micLevel = 0f,
            assistantName = "Penelope", greeting = "Good evening", ui = home, photo = null,
            onMic = {}, onPause = {}, onEnd = {}, onSuggestion = {}, onOpenAppSettings = {},
            onOpenGarage = {}, onOpenAdapter = {}, onOpenSettings = {}, onOpenResults = {}, onAddVehicle = {},
            onSelectVehicle = {}, onPhotoPicked = {}, aiStatus = AiStatus.Off,
            items = DashboardPlanner.itemsFor(level, DashboardSettings()), kind = DashboardKind.forLevel(level), now = NOW,
            clearCodes = if (level == ExplanationLevel.DIY) {
                { ClearCodesContent(ClearCodesUiState(ClearCodesAvailability(true, home.lastRead != null, null)), {}, {}, {}, {}) }
            } else {
                null
            },
            diyLive = diyLive, turns = emptyList(), profileName = if (level == ExplanationLevel.DIY) "Penelope Classic" else null,
        )
    }

    // ---- Audit ---------------------------------------------------------------------------------

    private fun audit(s: Shot) {
        val root = compose.onNodeWithTag(ROOT).fetchSemanticsNode()
        val density = s.density ?: compose.activity.resources.displayMetrics.density
        val left = root.boundsInRoot.left
        val right = root.boundsInRoot.right
        val navTop = runCatching { compose.onNodeWithTag(NAV).fetchSemanticsNode().boundsInRoot.top }.getOrNull()
        val problems = mutableListOf<String>()
        val clickables = mutableListOf<SemanticsNode>()

        fun inHorizontalScroller(n: SemanticsNode): Boolean {
            var p = n.parent
            while (p != null) {
                if (p.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null) return true
                p = p.parent
            }
            return false
        }
        fun name(n: SemanticsNode) = (
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty() +
                n.config.getOrNull(SemanticsProperties.Text)?.joinToString().orEmpty()
            ).ifBlank { "<unlabeled ${n.id}>" }

        fun visit(n: SemanticsNode) {
            val clickable = n.config.getOrNull(SemanticsActions.OnClick) != null
            val text = n.config.getOrNull(SemanticsProperties.Text)
            val b = n.boundsInRoot
            if ((clickable || text != null) && !inHorizontalScroller(n) && b.width > 0f) {
                if (b.left < left - 0.5f || b.right > right + 0.5f) problems += "\"${name(n)}\" leaves the screen (${b.left}..${b.right} of $left..$right)"
            }
            if (clickable) {
                clickables += n
                if (name(n).startsWith("<unlabeled")) problems += "${name(n)} has no TalkBack label"
                val w = n.size.width / density
                val h = n.size.height / density
                if (w < 47.5f || h < 47.5f) problems += "\"${name(n)}\" touch area is ${w.toInt()}x${h.toInt()} dp"
            }
            n.children.forEach(::visit)
        }
        visit(compose.onNodeWithTag(ROOT).fetchSemanticsNode())

        fun related(a: SemanticsNode, b: SemanticsNode): Boolean {
            var p = a.parent
            while (p != null) { if (p.id == b.id) return true; p = p.parent }
            p = b.parent
            while (p != null) { if (p.id == a.id) return true; p = p.parent }
            return false
        }
        // Content scrolled under the floating navigation bar is meant to pass beneath it.
        val content = clickables.filter { n -> navTop == null || n.boundsInRoot.top < navTop || isNav(n) }
        for (i in content.indices) for (j in i + 1 until content.size) {
            val a = content[i]
            val b = content[j]
            if (related(a, b) || isNav(a) != isNav(b)) continue
            val ow = minOf(a.boundsInRoot.right, b.boundsInRoot.right) - maxOf(a.boundsInRoot.left, b.boundsInRoot.left)
            val oh = minOf(a.boundsInRoot.bottom, b.boundsInRoot.bottom) - maxOf(a.boundsInRoot.top, b.boundsInRoot.top)
            if (ow > 1f && oh > 1f) problems += "\"${name(a)}\" overlaps \"${name(b)}\""
        }
        assertTrue("${s.fileName}:\n" + problems.distinct().joinToString("\n"), problems.isEmpty())
    }

    private fun isNav(n: SemanticsNode): Boolean {
        var p: SemanticsNode? = n
        while (p != null) {
            if (p.config.getOrNull(SemanticsProperties.TestTag) == NAV) return true
            p = p.parent
        }
        return false
    }
}

private const val NAV = "p1d_nav"
