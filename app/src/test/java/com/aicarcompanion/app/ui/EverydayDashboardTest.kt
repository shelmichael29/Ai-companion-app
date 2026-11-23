package com.aicarcompanion.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.ai.AiStatus
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.assistant.conversation.AssistantReply
import com.aicarcompanion.app.assistant.conversation.ReplyBasis
import com.aicarcompanion.app.dashboard.DashboardKind
import com.aicarcompanion.app.dashboard.DashboardPlanner
import com.aicarcompanion.app.data.local.ColorStyle
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.ThemeMode
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdTransport
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DiagnosticTroubleCode
import com.aicarcompanion.app.domain.model.DtcSeverity
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.TroubleCodeRead
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.ui.home.HealthAction
import com.aicarcompanion.app.ui.home.HealthTone
import com.aicarcompanion.app.ui.home.HomeContent
import com.aicarcompanion.app.ui.home.HomeUiState
import com.aicarcompanion.app.ui.home.everydayHealth
import com.aicarcompanion.app.ui.home.everydayStatus
import com.aicarcompanion.app.ui.home.liveLine
import com.aicarcompanion.app.ui.theme.CarCompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset

/** Everyday Driver dashboard: honest health wording, working actions, voice feedback and accessibility. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class EverydayDashboardTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val car = Vehicle(
        id = "bmw", year = 2011, make = "BMW", model = "528i", trim = "F10",
        engine = EngineSpec(3.0, 6, EngineLayout.INLINE, engineCode = "N52B30"), odometerMiles = 98_000,
    )
    private val connected = ObdConnectionState.Connected(ObdTransport.BLUETOOTH_CLASSIC, "OBDII", vehicleProtocol = "ISO 15765-4 CAN")
    private val read = TroubleCodeRead("s1", "bmw", Instant.parse("2026-11-16T21:34:00Z"), DataOrigin.OBD_BLUETOOTH, emptyList())
    private fun code(severity: DtcSeverity = DtcSeverity.MODERATE, origin: DataOrigin = DataOrigin.OBD_BLUETOOTH) =
        DiagnosticTroubleCode("P0171", "System too lean (bank 1)", headline = "Too much air or too little fuel", severity = severity, origin = origin)

    // ---- Health wording (never invented) ------------------------------------------------------

    @Test fun `all clear is only shown after a real code read`() {
        val notChecked = everydayHealth(car, emptyList(), null, ObdConnectionState.Disconnected)
        assertEquals("Not checked yet", notChecked.headline)
        assertEquals(HealthTone.UNKNOWN, notChecked.tone)
        assertEquals(HealthAction.CONNECT_ADAPTER, notChecked.action)
        assertNull(notChecked.updated)

        val ready = everydayHealth(car, emptyList(), null, connected)
        assertEquals("Ready to check", ready.headline)
        assertEquals(HealthAction.CHECK_CODES, ready.action)

        val clear = everydayHealth(car, emptyList(), read, connected, ZoneOffset.UTC)
        assertEquals("All clear", clear.headline)
        assertEquals(HealthTone.GOOD, clear.tone)
        assertEquals("Checked Nov 16, 9:34 PM", clear.updated)
    }

    @Test fun `codes are shown with plain meanings and severe ones are urgent`() {
        val h = everydayHealth(car, listOf(code()), read, connected)
        assertEquals("Needs attention", h.headline)
        assertEquals(listOf("P0171" to "Too much air or too little fuel"), h.codes)
        assertEquals(HealthAction.SEE_DETAILS, h.action)
        assertEquals(HealthTone.URGENT, everydayHealth(car, listOf(code(DtcSeverity.SEVERE)), read, connected).tone)
    }

    @Test fun `sample data is always labeled and no car means add one`() {
        val sample = everydayHealth(car, listOf(code(origin = DataOrigin.SAMPLE)), null, ObdConnectionState.Disconnected)
        assertTrue(sample.sample)
        assertTrue(sample.detail.startsWith("Sample data, not from your car."))
        val none = everydayHealth(null, emptyList(), null, ObdConnectionState.Disconnected)
        assertEquals(HealthAction.ADD_VEHICLE, none.action)
        assertFalse(none.sample)
    }

    @Test fun `voice feedback shows real words once`() {
        assertEquals("Listening…", everydayStatus(VoiceState.Listening("my brakes squeak"), "Penelope"))
        assertEquals("“my brakes squeak”", liveLine(VoiceState.Listening("my brakes squeak")))
        assertNull(liveLine(VoiceState.Listening("")))
        assertEquals("“is it safe”", liveLine(VoiceState.Processing("is it safe")))
        assertEquals("It's fine to drive.", liveLine(VoiceState.Speaking(AssistantReply("It's fine to drive.", ReplyBasis.CONVERSATION))))
        assertNull(liveLine(VoiceState.Idle))
    }

    // ---- The rendered dashboard ----------------------------------------------------------------

    private class Taps {
        var mic = 0; var asked: String? = null; var results = 0; var history = 0; var chat = 0; var adapter = 0; var extras = 0; var end = 0
    }

    private fun render(
        ui: HomeUiState = HomeUiState(vehicle = car, loaded = true),
        voice: VoiceState = VoiceState.Idle,
        active: Boolean = false,
        mode: ThemeMode = ThemeMode.DARK,
    ): Taps {
        val taps = Taps()
        compose.setContent {
            CarCompanionTheme(themeMode = mode, reduceMotion = true, colorStyle = ColorStyle.MIDNIGHT) {
                HomeContent(
                    contentPadding = PaddingValues(), voice = voice, conversationActive = active, micLevel = 0f,
                    assistantName = "Penelope", greeting = "Good evening", ui = ui, photo = null,
                    onMic = { taps.mic++ }, onPause = {}, onEnd = { taps.end++ }, onSuggestion = { taps.asked = it },
                    onOpenAppSettings = {}, onOpenGarage = {}, onOpenAdapter = { taps.adapter++ }, onOpenSettings = {},
                    onOpenResults = { taps.results++ }, onAddVehicle = {}, onSelectVehicle = {}, onPhotoPicked = {},
                    onOpenHistory = { taps.history++ }, aiStatus = AiStatus.Off, onOpenExtras = { taps.extras++ },
                    items = DashboardPlanner.defaults(ExplanationLevel.EVERYDAY), kind = DashboardKind.COMPANION,
                    onOpenChat = { taps.chat++ },
                )
            }
        }
        return taps
    }

    @Test fun quickActionsOpenRealFeatures() {
        val taps = render()
        compose.onNodeWithContentDescription("What's wrong with my car?. Describe what you notice").performScrollTo().performClick()
        assertEquals("What's wrong with my car?", taps.asked)
        compose.onNodeWithContentDescription("Check engine light. What the warning means").performScrollTo().performClick()
        assertEquals(1, taps.results)
        compose.onNodeWithContentDescription("Maintenance. Service records").performScrollTo().performClick()
        assertEquals(1, taps.history)
        compose.onNodeWithContentDescription("Ask Penelope. Talk or type").performScrollTo().performClick()
        assertEquals(1, taps.chat)
        compose.onNodeWithContentDescription("Extras, optional tools").performScrollTo().performClick()
        assertEquals(1, taps.extras)
    }

    @Test fun orbAndMicButtonBothStartTheRealConversation() {
        val taps = render()
        compose.onAllNodesWithContentDescription("Talk to Penelope")[0].performClick()
        compose.onAllNodesWithContentDescription("Talk to Penelope")[1].performScrollTo().performClick()
        assertEquals(2, taps.mic)
        compose.onAllNodesWithContentDescription("Talk to Penelope").onFirst()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "ready"))
    }

    @Test fun activeConversationShowsWordsAndEndControl() {
        val taps = render(voice = VoiceState.Listening("my car pulls to the left"), active = true)
        compose.onNodeWithText("Listening…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("“my car pulls to the left”").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("End conversation").performScrollTo().performClick()
        assertEquals(1, taps.end)
        compose.onNodeWithContentDescription("Pause conversation").assertExists()
    }

    @Test fun healthSectionIsHonestAndActionable() {
        val taps = render()
        compose.onNodeWithContentDescription("Vehicle health: Not checked yet").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Connect my adapter").performScrollTo().performClick()
        assertEquals(1, taps.adapter)
    }

    @Test fun codesShowOnTheDashboard() {
        render(HomeUiState(vehicle = car, codes = listOf(code()), lastRead = read, obdState = connected, loaded = true))
        compose.onNodeWithContentDescription("Vehicle health: Needs attention").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("P0171").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("See what it means").performScrollTo().assertIsDisplayed()
    }

    @Test fun touchTargetsAreAtLeast48dp() {
        render()
        for (label in listOf("Extras, optional tools", "Settings", "Type a question instead", "Maintenance. Service records")) {
            compose.onNodeWithContentDescription(label).performScrollTo().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        compose.onAllNodesWithContentDescription("Talk to Penelope")[1].assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test fun lightThemeRendersTheSameDashboard() {
        render(mode = ThemeMode.LIGHT)
        compose.onNodeWithText("Good evening").assertIsDisplayed()
        compose.onNodeWithContentDescription("Vehicle health: Not checked yet").performScrollTo().assertIsDisplayed()
    }

    @Test fun textWithNoColorOfItsOwnFollowsTheTheme() {
        // The root cause of the black-text bug: a plain Text outside any Material Surface.
        var mode by mutableStateOf(ThemeMode.DARK)
        var seen = Color.Unspecified
        compose.setContent {
            CarCompanionTheme(themeMode = mode) { seen = LocalContentColor.current; Text("x") }
        }
        compose.waitForIdle()
        val dark = seen
        mode = ThemeMode.LIGHT
        compose.waitForIdle()
        val light = seen
        assertTrue("dark mode text must be light, was $dark", dark.luminance() > 0.7f)
        assertTrue("light mode text must be dark, was $light", light.luminance() < 0.1f)
    }
}
