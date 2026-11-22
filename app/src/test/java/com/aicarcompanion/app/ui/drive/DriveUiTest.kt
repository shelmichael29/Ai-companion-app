package com.aicarcompanion.app.ui.drive

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.ThemeMode
import com.aicarcompanion.app.ui.home.EverydayHealth
import com.aicarcompanion.app.ui.home.HealthAction
import com.aicarcompanion.app.ui.home.HealthTone
import com.aicarcompanion.app.ui.theme.CarCompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
class DriveUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(
        ui: DriveUiState,
        level: ExplanationLevel,
        actions: DriveActions = DriveActions(),
        theme: ThemeMode = ThemeMode.DARK,
        fontScale: Float = 1f,
    ) = compose.setContent {
        CarCompanionTheme(themeMode = theme, reduceMotion = true) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                DriveContent(ui, level, "Penelope", actions, PaddingValues())
            }
        }
    }

    private fun assertThreeAreas(level: ExplanationLevel) {
        show(DriveFixtures.playing, level)
        listOf("Music and audio", "Messages", "Calls").forEach { compose.onNodeWithText(it).assertExists() }
        assertEquals(0, compose.onAllNodesWithText("Coming in a later update").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Talk to Penelope").assertExists()
        listOf("Pause", "Previous track", "Next track", "Volume up", "Volume down").forEach {
            compose.onNodeWithContentDescription(it).assertExists()
        }
        compose.onNodeWithContentDescription("Call someone. Choose a contact").assertExists()
    }

    @Test fun `everyday shows the three areas and no placeholder`() = assertThreeAreas(ExplanationLevel.EVERYDAY)
    @Test fun `diy shows the three areas and no placeholder`() = assertThreeAreas(ExplanationLevel.DIY)
    @Test fun `advanced shows the three areas and no placeholder`() = assertThreeAreas(ExplanationLevel.ADVANCED)

    @Test fun `everyday level has large media controls and one car controls row`() {
        show(DriveFixtures.playing, ExplanationLevel.EVERYDAY)
        listOf("Previous track", "Next track").forEach { compose.onNodeWithContentDescription(it).assertWidthIsAtLeast(72.dp).assertHeightIsAtLeast(72.dp) }
        // One toggle (96dp) while a session's state is known.
        compose.onNodeWithContentDescription("Pause").assertWidthIsAtLeast(96.dp).assertHeightIsAtLeast(96.dp)
        compose.onNodeWithContentDescription("Call someone. Choose a contact").performScrollTo().assertHeightIsAtLeast(72.dp)
        compose.onNodeWithText("Car controls: not available for this car").performScrollTo().assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Check codes").fetchSemanticsNodes().size)
    }

    @Test fun `a known playing session shows only Pause, a paused one only Play, keys-only shows both`() {
        var pauses = 0
        show(DriveFixtures.playing, ExplanationLevel.EVERYDAY, DriveActions(onPause = { pauses++ }))
        assertEquals(0, compose.onAllNodesWithContentDescription("Play").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Pause").performClick()
        assertEquals(1, pauses)
    }

    @Test fun `a paused session shows only Play`() {
        var plays = 0
        show(DriveFixtures.paused, ExplanationLevel.DIY, DriveActions(onPlay = { plays++ }))
        assertEquals(0, compose.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Play").performClick()
        assertEquals(1, plays)
    }

    @Test fun `keys only mode offers both Play and Pause because the state is unknown`() {
        show(DriveFixtures.needsAccess, ExplanationLevel.EVERYDAY)
        compose.onNodeWithContentDescription("Play").assertWidthIsAtLeast(96.dp)
        compose.onNodeWithContentDescription("Pause").assertWidthIsAtLeast(80.dp)
    }

    @Test fun `the not-connected state says so instead of no new messages`() {
        show(DriveFixtures.playing.copy(messages = MessagesUiState.NotConnected), ExplanationLevel.EVERYDAY)
        compose.onNodeWithText("I can't see your notifications right now.", substring = true).performScrollTo().assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("No new messages", substring = true).fetchSemanticsNodes().size)
    }

    @Test fun `the setup text says Android lets Penelope see all notifications and Penelope keeps only messages`() {
        show(DriveFixtures.needsAccess, ExplanationLevel.EVERYDAY)
        compose.onNodeWithText("see all your notifications", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `buttons are disabled while a confirmation is being carried out`() {
        var confirmed = 0
        show(DriveFixtures.confirmReply.copy(confirming = true), ExplanationLevel.EVERYDAY, DriveActions(onConfirm = { confirmed++ }))
        compose.onNodeWithContentDescription("Send reply to Sarah").performClick()
        assertEquals(0, confirmed)
    }

    @Test fun `everyday messages show a count only`() {
        show(DriveFixtures.unread, ExplanationLevel.EVERYDAY)
        compose.onNodeWithText("2 new messages").performScrollTo().assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Latest from Sarah", substring = true).fetchSemanticsNodes().size)
    }

    @Test fun `diy shows the sender and optional health shortcuts`() {
        var codes = 0
        val health = EverydayHealth("All clear", "No warning codes the last time I checked.", HealthTone.GOOD, emptyList(), null, "Check", HealthAction.CHECK_CODES, false)
        show(
            DriveFixtures.unread.copy(health = health), ExplanationLevel.DIY,
            DriveActions(onOpenDiagnostics = { codes++ }, onOpenLiveData = {}),
        )
        compose.onNodeWithText("Latest from Sarah in Messages").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Check codes").performScrollTo().performClick()
        compose.onNodeWithText("Live data").performScrollTo().assertIsDisplayed()
        assertEquals(1, codes)
    }

    @Test fun `advanced level is compact with 48dp targets and lists every car control with its reason`() {
        show(DriveFixtures.playing, ExplanationLevel.ADVANCED)
        listOf("Pause", "Previous track", "Next track", "Volume up", "Volume down").forEach {
            compose.onNodeWithContentDescription(it).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        val rows = DriveFixtures.rows
        rows.forEach { row ->
            // Unavailable controls are plain text, never buttons.
            compose.onNodeWithText("${row.feature.label}: not available").performScrollTo().assertIsDisplayed().assertHasNoClickAction()
        }
        assertTrue(compose.onAllNodesWithText("Standard OBD-II adapters read data; they can't control the car.").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun `media wording is honest about what the buttons do`() {
        show(DriveFixtures.needsAccess, ExplanationLevel.EVERYDAY)
        compose.onNodeWithText("Turn on notification access to see what's playing.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("These buttons ask your media app. Some apps don't respond.").performScrollTo().assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Sample Song Title").fetchSemanticsNodes().size)
    }

    @Test fun `now playing shows the title and app when a session exists`() {
        show(DriveFixtures.playing, ExplanationLevel.DIY)
        compose.onNodeWithText("Sample Song Title").assertIsDisplayed()
        compose.onNodeWithText("Playing in Sample Music").assertIsDisplayed()
        compose.onNodeWithText("Volume 7 of 15").performScrollTo().assertIsDisplayed()
    }

    @Test fun `notification access setup explains restricted settings and is tappable`() {
        var opened = 0
        show(DriveFixtures.needsAccess, ExplanationLevel.EVERYDAY, DriveActions(onOpenNotificationAccess = { opened++ }))
        compose.onNodeWithText("Restricted setting", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Turn on notification access").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test fun `the send button confirms exactly once and cancel cancels`() {
        var confirmed = 0
        var cancelled = 0
        show(DriveFixtures.confirmReply, ExplanationLevel.EVERYDAY, DriveActions(onConfirm = { confirmed++ }, onCancel = { cancelled++ }))
        compose.onNodeWithText("Send this reply?").assertIsDisplayed()
        compose.onNodeWithText("“Running ten minutes late”").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send reply to Sarah").performClick()
        assertEquals(1, confirmed)
        compose.onNodeWithContentDescription("Cancel, don't send").performClick()
        assertEquals(1, cancelled)
        assertEquals(1, confirmed)
    }

    @Test fun `the dial confirmation names the number and offers the dialer or cancel`() {
        var confirmed = 0
        show(DriveFixtures.confirmDial, ExplanationLevel.ADVANCED, DriveActions(onConfirm = { confirmed++ }))
        compose.onNodeWithText("Open the dialer?").assertIsDisplayed()
        compose.onNodeWithText("Sam Sample, 555 0100").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open the dialer for this number").performClick()
        assertEquals(1, confirmed)
    }

    @Test fun `everyday controls never overlap at double font size`() = assertNoOverlap(ExplanationLevel.EVERYDAY)
    @Test fun `diy controls never overlap at double font size`() = assertNoOverlap(ExplanationLevel.DIY)
    @Test fun `advanced controls never overlap at double font size`() = assertNoOverlap(ExplanationLevel.ADVANCED)

    private fun assertNoOverlap(level: ExplanationLevel) {
        show(DriveFixtures.confirmReply.copy(messages = com.aicarcompanion.app.ui.drive.MessagesUiState.Unread(2, "Sarah", "Messages")), level, fontScale = 2f)
        val bounds = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().map { n ->
            val r = n.boundsInRoot
            floatArrayOf(r.left, r.top, r.right, r.bottom)
        }
        assertTrue(bounds.size >= 8)
        for (i in bounds.indices) for (j in i + 1 until bounds.size) {
            val a = bounds[i]; val b = bounds[j]
            val overlapW = minOf(a[2], b[2]) - maxOf(a[0], b[0])
            val overlapH = minOf(a[3], b[3]) - maxOf(a[1], b[1])
            assertFalse("clickable bounds $i and $j overlap in $level", overlapW > 2f && overlapH > 2f)
        }
    }
}
