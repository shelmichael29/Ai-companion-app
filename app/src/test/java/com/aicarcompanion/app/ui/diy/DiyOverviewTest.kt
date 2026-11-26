package com.aicarcompanion.app.ui.diy

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
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
import com.aicarcompanion.app.domain.livedata.GaugeSpec
import com.aicarcompanion.app.domain.livedata.LiveSensorCatalog
import com.aicarcompanion.app.domain.livedata.SensorValueState
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DiagnosticTroubleCode
import com.aicarcompanion.app.domain.model.DtcSeverity
import com.aicarcompanion.app.domain.model.DtcStatus
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.MaintenanceRecord
import com.aicarcompanion.app.domain.model.TroubleCodeRead
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.ui.assistant.micActionLabel
import com.aicarcompanion.app.ui.assistant.statusText
import com.aicarcompanion.app.ui.home.DIY_DASHBOARD_TAG
import com.aicarcompanion.app.ui.home.DIY_ORB_TAG
import com.aicarcompanion.app.ui.home.HOME_SUGGESTIONS
import com.aicarcompanion.app.ui.home.HomeContent
import com.aicarcompanion.app.ui.home.HomeUiState
import com.aicarcompanion.app.ui.livedata.GaugeSemantics
import com.aicarcompanion.app.ui.theme.CarCompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate

/** The DIY Overview rendered through [HomeContent], at 360 dp wide (and 200% font where noted). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h800dp")
class DiyOverviewTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-11-16T21:40:00Z")
    private val car = Vehicle(
        id = "bmw", year = 2011, make = "BMW", model = "528i", vin = "WBAFR1C50BC000000",
        engine = EngineSpec(3.0, 6, EngineLayout.INLINE, engineCode = "N52B30"), odometerMiles = 98_000,
    )
    private val other = Vehicle(id = "civic", year = 2015, make = "Honda", model = "Civic", engine = EngineSpec(1.8, 4, EngineLayout.INLINE))
    private val bt = ObdTransport.BLUETOOTH_CLASSIC
    private val ecu = ObdConnectionState.Connected(bt, "OBDII", vehicleProtocol = "ISO 15765-4 CAN", adapterVersion = "ELM327 v1.5")
    private val read = TroubleCodeRead("s1", "bmw", Instant.parse("2026-11-16T21:34:00Z"), DataOrigin.OBD_BLUETOOTH, emptyList())
    private fun dtc(status: DtcStatus, code: String = "P0171") = DiagnosticTroubleCode(
        code, "System too lean (bank 1)", headline = "Too much air or too little fuel",
        status = status, severity = DtcSeverity.MODERATE, origin = DataOrigin.OBD_BLUETOOTH,
    )
    private val ui = HomeUiState(vehicle = car, vehicles = listOf(car), obdState = ecu, loaded = true)

    private val gauges4 = listOf(0x0C, 0x05, 0x0D, 0x42).map { GaugeSpec(it) }
    private val liveStates = mapOf(
        0x0C to SensorValueState.Live(780.0, now),
        0x05 to SensorValueState.Stale(92.0, now.minusSeconds(60)),
        0x0D to SensorValueState.Unsupported,
        0x42 to SensorValueState.TemporarilyUnavailable("No answer from the vehicle"),
    )

    private class Taps {
        var mic = 0; var pause = 0; var end = 0; var chat = 0; var settings = 0; var garage = 0; var adapter = 0
        var connection = 0; var liveData = 0; var results = 0; var diagnostics = 0; var history = 0; var drive = 0
        var addVehicle = 0; var appSettings = 0
        val asked = mutableListOf<String>(); val selected = mutableListOf<String>()
    }

    private val threeTurnsPlus = listOf(
        ConversationTurn(1, Speaker.USER, "oldest question", now),
        ConversationTurn(2, Speaker.ASSISTANT, "second answer", now),
        ConversationTurn(3, Speaker.USER, "third question", now),
        ConversationTurn(4, Speaker.ASSISTANT, "newest answer", now),
    )

    private fun render(
        ui: HomeUiState = this.ui,
        voice: VoiceState = VoiceState.Idle,
        live: DiyLiveInput? = DiyLiveInput(true, gauges4, liveStates),
        turns: List<ConversationTurn> = emptyList(),
        dashboard: DashboardSettings = DashboardSettings(),
        mode: ThemeMode = ThemeMode.DARK,
        fontScale: Float = 1f,
        profileName: String? = "Penelope Classic",
        aiStatus: AiStatus = AiStatus.Off,
        conversationActive: Boolean = false,
    ): Taps {
        val taps = Taps()
        compose.setContent {
            CarCompanionTheme(themeMode = mode, reduceMotion = true) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    HomeContent(
                        contentPadding = PaddingValues(), voice = voice, conversationActive = conversationActive, micLevel = 0f,
                        assistantName = "Penelope", greeting = "Hi", ui = ui, photo = null,
                        onMic = { taps.mic++ }, onPause = { taps.pause++ }, onEnd = { taps.end++ },
                        onSuggestion = { taps.asked += it }, onOpenAppSettings = { taps.appSettings++ },
                        onOpenGarage = { taps.garage++ }, onOpenAdapter = { taps.adapter++ }, onOpenSettings = { taps.settings++ },
                        onOpenResults = { taps.results++ }, onAddVehicle = { taps.addVehicle++ },
                        onSelectVehicle = { taps.selected += it }, onPhotoPicked = {},
                        onOpenHistory = { taps.history++ }, aiStatus = aiStatus,
                        items = DashboardPlanner.itemsFor(ExplanationLevel.DIY, dashboard), kind = DashboardKind.WORKSHOP,
                        onOpenChat = { taps.chat++ }, onOpenDiagnostics = { taps.diagnostics++ },
                        onOpenLiveData = { taps.liveData++ }, onOpenConnectionSettings = { taps.connection++ }, now = now,
                        diyLive = live, turns = turns, profileName = profileName, onOpenDrive = { taps.drive++ },
                    )
                }
            }
        }
        return taps
    }

    private val density get() = compose.activity.resources.displayMetrics.density

    // ---- Voice bar ----------------------------------------------------------------------------

    @Test fun overviewHasNoBigOrbAndExactlyOneTalkControl() {
        render()
        compose.onNodeWithTag(DIY_DASHBOARD_TAG).assertExists()
        compose.onNodeWithTag(DIY_ORB_TAG).assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Talk to Penelope").assertCountEquals(1)
    }

    private val speaking = VoiceState.Speaking(AssistantReply("Looks fine.", ReplyBasis.CONVERSATION))

    /** The bar's one line, the mic label, and which session controls show, for the real [voice] state. */
    private fun checkVoiceState(voice: VoiceState, expectedStatus: String = voice.statusText("Penelope")) {
        render(voice = voice)
        val h = compose.onNodeWithTag(DIY_VOICE_BAR_TAG).fetchSemanticsNode().size.height / density
        assertTrue("$voice bar is $h dp", h <= 72.5f)
        compose.onNodeWithTag(DIY_VOICE_STATUS_TAG).assertTextEquals(expectedStatus)
        compose.onAllNodesWithContentDescription(micActionLabel(voice, "Penelope")).assertCountEquals(1)
        val running = voice is VoiceState.Listening || voice is VoiceState.Speaking || voice is VoiceState.Processing
        val paused = voice is VoiceState.Paused
        compose.onAllNodesWithContentDescription("Pause conversation").assertCountEquals(if (running && !paused) 1 else 0)
        compose.onAllNodesWithContentDescription("End conversation").assertCountEquals(if (running || paused) 1 else 0)
    }

    @Test fun idleBarIsCompactAndReady() = checkVoiceState(VoiceState.Idle, "Ready. Tap the mic and start talking.")

    @Test fun listeningBarShowsWhatWasHeard() = checkVoiceState(VoiceState.Listening("check"))

    @Test fun slowProcessingBarSaysStillThinking() = checkVoiceState(VoiceState.Processing("check", slow = true))

    @Test fun speakingBarOffersInterrupt() = checkVoiceState(speaking)

    @Test fun pausedBarOffersResumeAndEnd() = checkVoiceState(VoiceState.Paused("Paused while you take a call"))

    @Test fun errorBarShowsTheRealMessage() = checkVoiceState(VoiceState.Error("The microphone is busy"))

    @Test fun micIsWiredAndPermissionButtonIsAbsentWithoutAPermissionProblem() {
        val taps = render(voice = VoiceState.Listening("check"))
        compose.onNodeWithContentDescription("Done talking").performClick()
        compose.onNodeWithContentDescription("Pause conversation").performClick()
        compose.onNodeWithContentDescription("End conversation").performClick()
        compose.onNodeWithContentDescription("Type a question instead").performClick()
        assertEquals(listOf(1, 1, 1, 1), listOf(taps.mic, taps.pause, taps.end, taps.chat))
        compose.onAllNodesWithText("Allow the microphone").assertCountEquals(0)
    }

    @Test fun permissionButtonOpensAppSettings() {
        val taps = render(voice = VoiceState.Error("I need the microphone", needsPermission = true))
        compose.onNodeWithText("Allow the microphone").performClick()
        assertEquals(1, taps.appSettings)
    }

    @Test fun expandingShowsTheLastThreeTurnsSuggestionsAndTheFullConversationLink() {
        val taps = render(turns = threeTurnsPlus, aiStatus = AiStatus.Off)
        // Collapsed: no suggestions, no conversation link.
        HOME_SUGGESTIONS.forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onAllNodesWithText("Open full conversation").assertCountEquals(0)
        assertEquals("collapsed", compose.onNodeWithContentDescription("Conversation details").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))

        compose.onNodeWithContentDescription("Conversation details").performClick()
        assertEquals("expanded", compose.onNodeWithContentDescription("Conversation details").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        compose.onNodeWithText("oldest question").assertDoesNotExist()
        listOf("second answer", "third question", "newest answer").forEach { compose.onNodeWithText(it).assertExists() }
        HOME_SUGGESTIONS.take(3).forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText(HOME_SUGGESTIONS[3]).assertDoesNotExist()
        compose.onNodeWithText("Basic answers").assertExists()
        compose.onNodeWithText("Personality: Penelope Classic").performClick()
        compose.onNodeWithText(HOME_SUGGESTIONS[0]).performClick()
        compose.onNodeWithText("Open full conversation").performClick()
        assertEquals(1, taps.settings)
        assertEquals(listOf(HOME_SUGGESTIONS[0]), taps.asked)
        assertEquals(1, taps.chat)

        compose.onNodeWithContentDescription("Conversation details").performClick()
        compose.onAllNodesWithText("Open full conversation").assertCountEquals(0)
    }

    // ---- Connection ---------------------------------------------------------------------------

    private fun hasNoText(fragment: String) =
        compose.onAllNodes(SemanticsMatcher("text contains $fragment") { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(fragment, ignoreCase = true) } == true
        }).assertCountEquals(0)

    @Test fun bluetoothAloneNeverReadsAsTheVehicleResponding() {
        render(ui = ui.copy(obdState = ObdConnectionState.Connecting(bt, "OBDII", ConnectPhase.LINK_OPEN)), live = DiyLiveInput(true, gauges4, emptyMap()))
        compose.onNodeWithContentDescription("Bluetooth link: Connected").assertExists()
        compose.onNodeWithContentDescription("Vehicle ECU: Waiting").assertExists()
        compose.onNodeWithText("Bluetooth connected. Setting up the adapter").assertExists()
        hasNoText("responding")
        hasNoText("Responding")
    }

    @Test fun adapterReadyWithoutAVehicleAnswerSaysSoAndMentionsTheIgnition() {
        render(ui = ui.copy(obdState = ObdConnectionState.Connected(bt, "OBDII", vehicleProtocol = null, adapterVersion = "ELM327 v1.5")))
        compose.onNodeWithContentDescription("Adapter setup: Ready").assertExists()
        compose.onNodeWithContentDescription("Vehicle ECU: No answer yet").assertExists()
        compose.onNodeWithText("Is the ignition on?", substring = true).assertExists()
        compose.onNodeWithText("Protocol:", substring = true).assertDoesNotExist()
    }

    @Test fun anAnsweringVehicleShowsProtocolAndAdapterAndNoAddress() {
        render()
        compose.onNodeWithContentDescription("Vehicle ECU: Responding").assertExists()
        compose.onNodeWithText("Protocol: ISO 15765-4 CAN").assertExists()
        compose.onNodeWithText("Adapter: ELM327 v1.5").assertExists()
        hasNoText("WBAFR1C50BC000000") // the VIN is never on the Overview
    }

    @Test fun staleDataShowsItsAge() {
        render(live = DiyLiveInput(true, gauges4, mapOf(0x0C to SensorValueState.Stale(780.0, now.minusSeconds(60)))))
        compose.onNodeWithText("Data stale").assertExists()
        compose.onNodeWithText("Last data: 1 min ago").assertExists()
        compose.onNodeWithContentDescription("Vehicle ECU: Stale").assertExists()
    }

    @Test fun aFailedConnectionIsShownInPlainWords() {
        render(ui = ui.copy(obdState = ObdConnectionState.Failed("Your adapter didn't answer in time.", ConnectFailure.TIMEOUT, "detail")))
        compose.onNodeWithText("Connection problem").assertExists()
        compose.onNodeWithText("Your adapter didn't answer in time.").assertExists()
        compose.onNodeWithContentDescription("Bluetooth link: Failed").assertExists()
        hasNoText("detail")
    }

    @Test fun sampleReadingsNeverMakeTheConnectionLookBetter() {
        val sample = gauges4.associate { it.pid to SensorValueState.Sample(1.0, now) as SensorValueState }
        render(ui = ui.copy(obdState = ObdConnectionState.Disconnected), live = DiyLiveInput(true, gauges4, sample))
        compose.onNodeWithText("No adapter connected").assertExists()
        compose.onNodeWithContentDescription("Vehicle ECU: Waiting").assertExists()
    }

    @Test fun connectionButtonsOpenTheRealScreens() {
        val taps = render()
        compose.onNodeWithText("Choose adapter").performScrollTo().performClick()
        compose.onNodeWithText("Connection settings").performScrollTo().performClick()
        assertEquals(1, taps.adapter)
        assertEquals(1, taps.connection)
    }

    // ---- Health -------------------------------------------------------------------------------

    @Test fun codeCountsAreSpokenAndCodeRowsOpenDiagnostics() {
        val taps = render(ui = ui.copy(codes = listOf(dtc(DtcStatus.STORED), dtc(DtcStatus.PENDING, "P0455")), lastRead = read))
        compose.onNodeWithContentDescription("Codes: 1 stored, 1 pending, 0 permanent", substring = true).assertExists()
        compose.onNodeWithContentDescription("Check engine light: likely on", substring = true).assertExists()
        compose.onNodeWithContentDescription("P0171, Too much air or too little fuel, Stored").performScrollTo().performClick()
        assertEquals(1, taps.diagnostics)
        compose.onNodeWithText("Read codes").performScrollTo().performClick()
        assertEquals(1, taps.results)
    }

    @Test fun nothingIsClaimedAboutCodesBeforeTheyAreRead() {
        render()
        compose.onNodeWithText("Codes not read yet").assertExists()
        compose.onNodeWithContentDescription("Codes: not read yet", substring = true).assertExists()
    }

    @Test fun atMostThreeCodeRowsAreShown() {
        val codes = listOf("P0171", "P0172", "P0300", "P0420").map { dtc(DtcStatus.STORED, it) }
        render(ui = ui.copy(codes = codes, lastRead = read))
        compose.onNodeWithText("P0300").assertExists()
        compose.onNodeWithText("P0420").assertDoesNotExist()
    }

    // ---- Live strip ---------------------------------------------------------------------------

    @Test fun everyStripStateIsHonestAndDescribed() {
        render()
        listOf(0x0C, 0x05, 0x0D, 0x42).forEach { pid ->
            val sensor = LiveSensorCatalog.find(pid)!!
            val description = GaugeSemantics.gaugeDescription(sensor, liveStates.getValue(pid), null, now)
            compose.onNodeWithContentDescription(description).assertExists()
        }
        compose.onNodeWithContentDescription("Engine speed, 780 revolutions per minute, live").assertExists()
        compose.onNodeWithText("Stale · 1 min ago").assertExists()
        compose.onNodeWithText("Not supported").assertExists()
        compose.onNodeWithText("No answer").assertExists()
    }

    @Test fun disconnectedAndSampleStatesAreLabeled() {
        val states = mapOf(0x0C to SensorValueState.Disconnected, 0x05 to SensorValueState.Sample(90.0, now))
        render(live = DiyLiveInput(true, listOf(GaugeSpec(0x0C), GaugeSpec(0x05)), states))
        compose.onNodeWithContentDescription("Engine speed, disconnected, no reading").assertExists()
        compose.onNodeWithContentDescription("Coolant temperature, 90 degrees Celsius, sample data, not from your vehicle").assertExists()
        compose.onNodeWithText("Sample data").assertExists()
        compose.onNodeWithText("Disconnected").assertExists()
    }

    @Test fun onlyTheFirstFourGaugesOfTheLayoutAreShown() {
        val six = listOf(0x0C, 0x0D, 0x05, 0x42, 0x2F, 0x04).map { GaugeSpec(it) }
        val states = six.associate { it.pid to SensorValueState.Live(10.0, now) as SensorValueState }
        render(live = DiyLiveInput(true, six, states))
        compose.onNodeWithContentDescription("Control module voltage", substring = true).assertExists()
        compose.onNodeWithContentDescription("Fuel level", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Calculated engine load", substring = true).assertDoesNotExist()
    }

    @Test fun withoutLiveDataInputNoValueIsShown() {
        val taps = render(live = null)
        compose.onNodeWithText("Open Live Data to see readings").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Engine speed", substring = true).assertDoesNotExist()
        assertEquals(1, taps.liveData)
    }

    @Test fun stripLinksOpenLiveData() {
        val taps = render()
        compose.onNodeWithText("Open Live Data").performScrollTo().performClick()
        compose.onNodeWithText("Edit gauges").performScrollTo().performClick()
        assertEquals(2, taps.liveData)
    }

    // ---- Header, shortcuts, maintenance -------------------------------------------------------

    @Test fun headerOpensTheGarageOrAddsAVehicleAndNeverShowsTheVin() {
        val taps = render()
        compose.onNodeWithText("2011 BMW 528i · N52B30 inline-6").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        assertEquals(1, taps.garage)
        assertEquals(1, taps.settings)
        hasNoText("WBAFR1C50BC000000")
    }

    @Test fun headerAddsAVehicleWhenThereIsNone() {
        val none = render(ui = HomeUiState(loaded = true))
        compose.onNodeWithText("No vehicle selected").performClick()
        assertEquals(1, none.addVehicle)
    }

    @Test fun severalVehiclesOfferASwitcher() {
        val taps = render(ui = ui.copy(vehicles = listOf(car, other)))
        compose.onNodeWithContentDescription("Switch vehicle").performClick()
        compose.onNodeWithText("2015 Honda Civic").performClick()
        assertEquals(listOf("civic"), taps.selected)
    }

    @Test fun oneVehicleHasNoSwitcher() {
        render()
        compose.onAllNodesWithContentDescription("Switch vehicle").assertCountEquals(0)
    }

    @Test fun driveAndCustomizeShortcutsWork() {
        val taps = render()
        compose.onNodeWithContentDescription("Drive, hands-free tools for the road").performScrollTo().performClick()
        compose.onNodeWithText("Customize").performScrollTo().performClick()
        assertEquals(1, taps.drive)
        assertEquals(1, taps.settings)
    }

    @Test fun maintenanceKeepsItsWordsAndLinksToHistory() {
        val record = MaintenanceRecord("m1", "bmw", LocalDate.of(2025, 9, 14), "Oil and filter change", odometerMiles = 61_800, origin = DataOrigin.USER_ENTERED)
        val taps = render(ui = ui.copy(lastService = record, lastRead = read))
        compose.onNodeWithText("Last service: Oil and filter change").performScrollTo().assertExists()
        compose.onNodeWithText("Sep 14, 2025 · 61,800 mi").assertExists()
        compose.onNodeWithText("Last code read:", substring = true).assertExists()
        compose.onNodeWithText("Open History").performScrollTo().performClick()
        assertEquals(1, taps.history)
    }

    @Test fun maintenanceSaysSoWhenThereAreNoRecords() {
        render()
        compose.onNodeWithText("No service records yet.").assertExists()
        compose.onNodeWithText("No code reads yet.").assertExists()
    }

    // ---- Layout and accessibility -------------------------------------------------------------

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
        val clickables = nodes.filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
        for (i in clickables.indices) for (j in i + 1 until clickables.size) {
            val a = clickables[i].boundsInRoot
            val b = clickables[j].boundsInRoot
            val overlapW = minOf(a.right, b.right) - maxOf(a.left, b.left)
            val overlapH = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            assertTrue("controls ${clickables[i].id} and ${clickables[j].id} overlap", overlapW <= 1f || overlapH <= 1f)
        }
    }

    @Test fun nothingOverlapsOrLeavesTheScreenAtDoubleFont() {
        render(
            ui = ui.copy(codes = listOf(dtc(DtcStatus.STORED), dtc(DtcStatus.PENDING, "P0455")), lastRead = read),
            voice = VoiceState.Listening("check"), turns = threeTurnsPlus, fontScale = 2f,
        )
        compose.onNodeWithContentDescription("Conversation details").performClick()
        assertNothingLeavesTheScreen()
        val barHeight = compose.onNodeWithTag(DIY_VOICE_BAR_TAG).fetchSemanticsNode().size.height / density
        assertTrue("voice bar at 200% font is $barHeight dp", barHeight <= 130f)
    }

    @Test fun lightThemeAtDoubleFontFits() {
        render(mode = ThemeMode.LIGHT, fontScale = 2f, voice = VoiceState.Error("I need the microphone", needsPermission = true))
        assertNothingLeavesTheScreen()
    }

    @Test fun everyClickableIsAtLeast48dpAndLabelled() {
        render(ui = ui.copy(codes = listOf(dtc(DtcStatus.STORED)), lastRead = read, vehicles = listOf(car, other)), voice = VoiceState.Speaking(AssistantReply("ok", ReplyBasis.CONVERSATION)), turns = threeTurnsPlus)
        compose.onNodeWithContentDescription("Conversation details").performClick()
        compose.waitForIdle()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes().forEach { n ->
            assertTrue("node ${n.id} too small ${n.size}", n.size.width / density >= 47.5f && n.size.height / density >= 47.5f)
            val labelled = n.config.getOrNull(SemanticsProperties.ContentDescription) != null || n.config.getOrNull(SemanticsProperties.Text) != null
            assertTrue("node ${n.id} has no label", labelled)
        }
    }

    @Test fun panelsAreHeadingsAndShortcutChipsAreButtons() {
        render()
        val headings = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
        listOf("Connection", "Vehicle health", "Live data", "History and maintenance", "Tools and shortcuts").forEach {
            assertTrue("$it is not a heading in $headings", it in headings)
        }
        val chip = compose.onNodeWithContentDescription("Car Health, Not checked").performScrollTo().fetchSemanticsNode()
        assertEquals(Role.Button, chip.config.getOrNull(SemanticsProperties.Role))
    }
}
