package com.aicarcompanion.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.aicarcompanion.app.ai.AiStatus
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.dashboard.DashboardItem
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
import com.aicarcompanion.app.domain.model.MaintenanceRecord
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.domain.model.TroubleCodeRead
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.ui.home.ADVANCED_DASHBOARD_TAG
import com.aicarcompanion.app.ui.home.ADVANCED_MIC_TAG
import com.aicarcompanion.app.ui.home.DIY_DASHBOARD_TAG
import com.aicarcompanion.app.ui.home.DIY_ORB_TAG
import com.aicarcompanion.app.ui.home.DiyDashboard
import com.aicarcompanion.app.ui.home.ProDashboardActions
import com.aicarcompanion.app.ui.home.EVERYDAY_DASHBOARD_TAG
import com.aicarcompanion.app.ui.home.HOME_SUGGESTIONS
import com.aicarcompanion.app.ui.home.HomeContent
import com.aicarcompanion.app.ui.home.HomeUiState
import com.aicarcompanion.app.ui.theme.CarCompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

/** The three level dashboards: each renders its own layout, with honest values, at 360 dp and 2x font. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h800dp")
class ProDashboardsTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-11-16T21:40:00Z")
    private val car = Vehicle(
        id = "bmw", year = 2011, make = "BMW", model = "528i",
        engine = EngineSpec(3.0, 6, EngineLayout.INLINE, engineCode = "N52B30"), odometerMiles = 98_000,
    )
    private val connected = ObdConnectionState.Connected(ObdTransport.BLUETOOTH_CLASSIC, "OBDII", vehicleProtocol = "ISO 15765-4 CAN")
    private val live = listOf(
        SensorReading("Engine speed", 780.0, "rpm", now, DataOrigin.OBD_BLUETOOTH, "010C"),
        SensorReading("Coolant temperature", 92.0, "°C", now, DataOrigin.OBD_BLUETOOTH, "0105"),
        SensorReading("Control module voltage", 14.2, "V", now, DataOrigin.OBD_BLUETOOTH, "0142"),
    )
    private val sample = listOf(
        SensorReading("Coolant temperature", 90.0, "°C", now, DataOrigin.SAMPLE),
        SensorReading("Control module voltage", 14.1, "V", now, DataOrigin.SAMPLE),
    )
    private val read = TroubleCodeRead("s1", "bmw", Instant.parse("2026-11-16T21:34:00Z"), DataOrigin.OBD_BLUETOOTH, emptyList())
    private fun dtc(status: DtcStatus = DtcStatus.STORED) = DiagnosticTroubleCode(
        "P0171", "System too lean (bank 1)", headline = "Too much air or too little fuel",
        status = status, severity = DtcSeverity.MODERATE, origin = DataOrigin.OBD_BLUETOOTH,
    )
    private val liveUi = HomeUiState(vehicle = car, obdState = connected, readings = live, loaded = true)

    private class Taps {
        var mic = 0; var results = 0; var diagnostics = 0; var liveData = 0; var adapter = 0; var history = 0
    }

    private fun render(
        level: ExplanationLevel,
        ui: HomeUiState = liveUi,
        voice: VoiceState = VoiceState.Idle,
        dashboard: DashboardSettings = DashboardSettings(),
        mode: ThemeMode = ThemeMode.DARK,
        fontScale: Float = 1f,
    ): Taps {
        val taps = Taps()
        compose.setContent {
            CarCompanionTheme(themeMode = mode, reduceMotion = true) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    HomeContent(
                        contentPadding = PaddingValues(), voice = voice, conversationActive = false, micLevel = 0f,
                        assistantName = "Penelope", greeting = "Hi", ui = ui, photo = null,
                        onMic = { taps.mic++ }, onPause = {}, onEnd = {}, onSuggestion = {}, onOpenAppSettings = {},
                        onOpenGarage = {}, onOpenAdapter = { taps.adapter++ }, onOpenSettings = {},
                        onOpenResults = { taps.results++ }, onAddVehicle = {}, onSelectVehicle = {}, onPhotoPicked = {},
                        onOpenHistory = { taps.history++ }, aiStatus = AiStatus.Off,
                        items = DashboardPlanner.itemsFor(level, dashboard), kind = DashboardKind.forLevel(level),
                        onOpenDiagnostics = { taps.diagnostics++ }, onOpenLiveData = { taps.liveData++ }, now = now,
                    )
                }
            }
        }
        return taps
    }

    /**
     * The preserved legacy DIY dashboard ([DiyDashboard]): no longer routed since Phase 1C, kept until the
     * cleanup stage. These tests keep their original assertions so the component stays correct.
     */
    private fun renderLegacyDiy(
        ui: HomeUiState = liveUi,
        voice: VoiceState = VoiceState.Idle,
        dashboard: DashboardSettings = DashboardSettings(),
    ): Taps {
        val taps = Taps()
        compose.setContent {
            CarCompanionTheme(themeMode = ThemeMode.DARK, reduceMotion = true) {
                DiyDashboard(
                    contentPadding = PaddingValues(), voice = voice, conversationActive = false, micLevel = 0f,
                    assistantName = "Penelope", ui = ui, items = DashboardPlanner.itemsFor(ExplanationLevel.DIY, dashboard),
                    actions = ProDashboardActions(
                        onMic = { taps.mic++ }, onPause = {}, onEnd = {}, onAsk = {}, onOpenChat = {},
                        onOpenResults = { taps.results++ }, onOpenDiagnostics = { taps.diagnostics++ },
                        onOpenLiveData = { taps.liveData++ }, onOpenAdapter = { taps.adapter++ }, onOpenHistory = { taps.history++ },
                        onOpenGarage = {}, onOpenSettings = {}, onOpenExtras = {}, onOpenConnectionSettings = {},
                        onAddVehicle = {}, onOpenAppSettings = {},
                    ),
                    now = now,
                )
            }
        }
        return taps
    }

    private fun assertTag(vararg present: String, absent: List<String> = emptyList()) {
        present.forEach { compose.onNodeWithTag(it).assertExists() }
        absent.forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
    }

    // ---- Each level has its own dashboard ----------------------------------------------------

    @Test fun eachLevelRendersItsOwnDashboard() {
        render(ExplanationLevel.EVERYDAY)
        assertTag(EVERYDAY_DASHBOARD_TAG, absent = listOf(DIY_DASHBOARD_TAG, ADVANCED_DASHBOARD_TAG))
    }

    @Test fun diyRendersItsOwnDashboard() {
        render(ExplanationLevel.DIY)
        assertTag(DIY_DASHBOARD_TAG, absent = listOf(EVERYDAY_DASHBOARD_TAG, ADVANCED_DASHBOARD_TAG))
    }

    @Test fun advancedRendersItsOwnDashboard() {
        render(ExplanationLevel.ADVANCED)
        assertTag(ADVANCED_DASHBOARD_TAG, absent = listOf(EVERYDAY_DASHBOARD_TAG, DIY_DASHBOARD_TAG))
    }

    // ---- Advanced: no orb, no suggestions, a working mic ----------------------------------------

    @Test fun advancedHasNoSuggestionChipsAndNoOrbButAMicThatTalks() {
        val taps = render(ExplanationLevel.ADVANCED)
        HOME_SUGGESTIONS.forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithText("More ideas").assertDoesNotExist()
        compose.onNodeWithTag(DIY_ORB_TAG).assertDoesNotExist()
        // The only "Talk to Penelope" control is the compact mic button; a big orb would be a second one.
        compose.onAllNodesWithContentDescription("Talk to Penelope").assertCountEquals(1)
        compose.onNodeWithTag(ADVANCED_MIC_TAG).performClick()
        assertEquals(1, taps.mic)
    }

    @Test fun advancedMicButtonDescribesVoiceState() {
        render(ExplanationLevel.ADVANCED, voice = VoiceState.Listening())
        val node = compose.onNodeWithTag(ADVANCED_MIC_TAG).fetchSemanticsNode()
        assertEquals("listening", node.config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals("Done talking", node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString())
    }

    @Test fun advancedOffersTheFullConversation() {
        render(ExplanationLevel.ADVANCED)
        compose.onNodeWithContentDescription("Open the conversation with Penelope").assertExists()
    }

    // ---- DIY: medium orb, health card, shortcuts -----------------------------------------------

    @Test fun diyShowsMediumOrbHealthCardAndAtMostThreeSuggestions() {
        val taps = renderLegacyDiy(ui = liveUi.copy(codes = listOf(dtc()), lastRead = read))
        compose.onNodeWithTag(DIY_ORB_TAG).assertExists()
        val orbHeight = compose.onNodeWithTag(DIY_ORB_TAG).fetchSemanticsNode().size.height
        val dp = orbHeight / compose.activity.resources.displayMetrics.density
        assertTrue("orb is medium (was $dp dp)", dp in 100f..200f)
        compose.onNodeWithTag("diy_health").assertExists()
        compose.onNodeWithText("P0171").assertExists()
        compose.onNodeWithText("System too lean (bank 1)").assertExists()
        HOME_SUGGESTIONS.take(3).forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText(HOME_SUGGESTIONS[3]).assertDoesNotExist()
        compose.onNodeWithText("Read codes").performScrollTo().performClick()
        assertEquals(1, taps.results)
    }

    @Test fun diyDiagnosticShortcutsOpenRealScreens() {
        val taps = renderLegacyDiy()
        compose.onNodeWithContentDescription("Diagnostics, Read and explain codes").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Live readings, Sensors from the car").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Adapter connection, Connected", substring = true).performScrollTo().performClick()
        assertEquals(1, taps.diagnostics)
        assertEquals(1, taps.liveData)
        assertEquals(1, taps.adapter)
    }

    @Test fun diyShowsLastServiceAndLinksToHistory() {
        val record = MaintenanceRecord("m1", "bmw", LocalDate.of(2025, 9, 14), "Oil and filter change", odometerMiles = 61_800, origin = DataOrigin.USER_ENTERED)
        val taps = render(ExplanationLevel.DIY, ui = liveUi.copy(lastService = record))
        compose.onNodeWithText("Last service: Oil and filter change").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sep 14, 2025 · 61,800 mi").assertExists()
        compose.onNodeWithText("Open History").performScrollTo().performClick()
        assertEquals(1, taps.history)
    }

    @Test fun diyWithoutServiceRecordsSaysSo() {
        render(ExplanationLevel.DIY)
        compose.onNodeWithText("No service records yet.").performScrollTo().assertExists()
    }

    // ---- Real values only ------------------------------------------------------------------------

    @Test fun diyAndAdvancedShowRealReadings() {
        renderLegacyDiy()
        compose.onNodeWithContentDescription("Coolant temperature, 92 degrees Celsius, PID 05").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Control module voltage, 14.2 volts, PID 42").performScrollTo().assertExists()
    }

    @Test fun advancedShowsTilesWithSpokenLabelsAndBattery() {
        render(ExplanationLevel.ADVANCED)
        compose.onNodeWithContentDescription("Coolant temperature, 92 degrees Celsius, PID 05").assertExists()
        compose.onNodeWithContentDescription("Engine speed, 780 revolutions per minute, PID 0C").assertExists()
        compose.onNodeWithContentDescription("Battery voltage: 14.2 volts, Control module").assertExists()
    }

    @Test fun nothingIsShownWhenDisconnectedEvenWithOldReadings() {
        val ui = HomeUiState(vehicle = car, obdState = ObdConnectionState.Disconnected, readings = live, loaded = true)
        render(ExplanationLevel.ADVANCED, ui = ui)
        compose.onNodeWithText("Connect the adapter to see live data").assertExists()
        compose.onNodeWithContentDescription("Coolant temperature", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Battery voltage: no live data").assertExists()
        compose.onNodeWithContentDescription("Readiness: not read yet").assertExists()
    }

    @Test fun diyShowsDashWithReasonWhenDisconnected() {
        renderLegacyDiy(ui = HomeUiState(vehicle = car, loaded = true))
        compose.onNodeWithContentDescription("Battery voltage, no reading. Connect your adapter").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Coolant temperature, no reading. Connect your adapter").assertExists()
        compose.onAllNodesWithText("Connect your adapter").assertCountEquals(2)
    }

    @Test fun sampleReadingsAreLabeledOnBothDashboards() {
        val ui = HomeUiState(vehicle = car, readings = sample, loaded = true)
        render(ExplanationLevel.ADVANCED, ui = ui)
        compose.onNodeWithText("SAMPLE DATA").assertExists()
        compose.onNodeWithContentDescription("Coolant temperature, 90 degrees Celsius, sample data").assertExists()
    }

    @Test fun diySampleReadingsAreLabeled() {
        renderLegacyDiy(ui = HomeUiState(vehicle = car, readings = sample, loaded = true))
        compose.onAllNodesWithText("Sample data").assertCountEquals(2)
    }

    @Test fun advancedStatusStripCountsStoredPendingAndPermanentFromTheLastRead() {
        val ui = liveUi.copy(codes = listOf(dtc(), dtc(DtcStatus.PENDING)), lastRead = read)
        render(ExplanationLevel.ADVANCED, ui = ui)
        compose.onNodeWithContentDescription("Check engine: on, stored code present").assertExists()
        compose.onNodeWithContentDescription("Codes: 1 stored, 1 pending, 0 permanent").assertExists()
        compose.onNodeWithContentDescription("P0171, Too much air or too little fuel, Stored").assertExists()
    }

    @Test fun advancedDoesNotClaimAnythingBeforeCodesAreRead() {
        render(ExplanationLevel.ADVANCED, ui = HomeUiState(vehicle = car, loaded = true))
        compose.onNodeWithContentDescription("Check engine: not read yet").assertExists()
        compose.onNodeWithContentDescription("Codes: not read yet").assertExists()
    }

    // ---- Shortcuts follow Easy / Customizable -----------------------------------------------------

    @Test fun easyDefaultsAppearOnBothDashboards() {
        render(ExplanationLevel.DIY)
        compose.onNodeWithContentDescription("Car Health, Not checked").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Extras, optional tools").performScrollTo().assertExists()
    }

    @Test fun advancedEasyDefaultsIncludeCarHealthAndConnection() {
        render(ExplanationLevel.ADVANCED)
        compose.onNodeWithContentDescription("Car Health, Not checked").assertExists()
        compose.onNodeWithContentDescription("Connection, protocol, timing and adapter test").assertExists()
    }

    private fun customizableShowsMileage(level: ExplanationLevel) {
        val d = DashboardPlanner.setShown(DashboardSettings(), level, DashboardItem.MILEAGE_LOG, true)
        val taps = render(level, dashboard = DashboardPlanner.setShown(d, level, DashboardItem.HISTORY, false))
        compose.onNodeWithContentDescription("Mileage, mileage log").performScrollTo().assertExists().performClick()
        assertEquals(1, taps.history)
        compose.onNodeWithContentDescription("History, service records and code reads").assertDoesNotExist()
        compose.onNodeWithContentDescription("Car Health", substring = true).assertExists()
    }

    @Test fun customizableItemsAppearOnDiy() = customizableShowsMileage(ExplanationLevel.DIY)

    @Test fun customizableItemsAppearOnAdvanced() = customizableShowsMileage(ExplanationLevel.ADVANCED)

    // ---- Vehicle line ------------------------------------------------------------------------------

    @Test fun vehicleLineNeverSaysV6ForAnUnknownLayout() {
        val unknown = car.copy(engine = EngineSpec(3.0, 6, EngineLayout.UNKNOWN, engineCode = "N52B30"))
        render(ExplanationLevel.ADVANCED, ui = liveUi.copy(vehicle = unknown))
        compose.onNodeWithText("2011 BMW 528i · N52B30 6-cyl").assertExists()
        compose.onNodeWithText("V6", substring = true).assertDoesNotExist()
    }

    @Test fun vehicleLineShowsInlineSixForTheBmw() {
        render(ExplanationLevel.ADVANCED)
        compose.onNodeWithText("2011 BMW 528i · N52B30 inline-6").assertExists()
    }

    // ---- Large font and narrow width ---------------------------------------------------------------

    private fun assertNothingLeavesTheScreen() {
        compose.waitForIdle()
        val rootWidth = compose.onRoot().fetchSemanticsNode().boundsInRoot.right
        val nodes = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick).or(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)),
        ).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
        nodes.forEach { n ->
            val b = n.boundsInRoot
            assertTrue("node ${n.id} starts at ${b.left}", b.left >= -0.5f)
            assertTrue("node ${n.id} ends at ${b.right} of $rootWidth", b.right <= rootWidth + 0.5f)
        }
        // Clickable controls never overlap each other.
        val clickables = nodes.filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
        for (i in clickables.indices) for (j in i + 1 until clickables.size) {
            val a = clickables[i].boundsInRoot
            val b = clickables[j].boundsInRoot
            val overlapW = minOf(a.right, b.right) - maxOf(a.left, b.left)
            val overlapH = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            assertTrue("controls ${clickables[i].id} and ${clickables[j].id} overlap", overlapW <= 1f || overlapH <= 1f)
        }
    }

    @Test fun advancedFitsAt360dpWithDoubleFont() {
        render(ExplanationLevel.ADVANCED, ui = liveUi.copy(codes = listOf(dtc()), lastRead = read), fontScale = 2f)
        assertNothingLeavesTheScreen()
        compose.onNodeWithTag(ADVANCED_MIC_TAG).assertExists()
        compose.onNodeWithContentDescription("Coolant temperature, 92 degrees Celsius, PID 05").assertExists()
    }

    @Test fun diyFitsAt360dpWithDoubleFont() {
        render(ExplanationLevel.DIY, ui = liveUi.copy(codes = listOf(dtc()), lastRead = read), fontScale = 2f)
        assertNothingLeavesTheScreen()
    }

    @Test fun advancedFitsInLightTheme() {
        render(ExplanationLevel.ADVANCED, mode = ThemeMode.LIGHT, fontScale = 2f)
        assertNothingLeavesTheScreen()
    }

    private fun assertClickablesAre48dp(level: ExplanationLevel) {
        render(level, ui = liveUi.copy(codes = listOf(dtc()), lastRead = read))
        compose.waitForIdle()
        val density = compose.activity.resources.displayMetrics.density
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes().forEach { n ->
            assertTrue("$level node ${n.id} too small ${n.size}", n.size.width / density >= 47.5f && n.size.height / density >= 47.5f)
        }
    }

    @Test fun diyClickablesAreAtLeast48dp() = assertClickablesAre48dp(ExplanationLevel.DIY)

    @Test fun advancedClickablesAreAtLeast48dp() = assertClickablesAre48dp(ExplanationLevel.ADVANCED)
}
