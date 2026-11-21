package com.aicarcompanion.app.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdTransport
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.SensorReading
import com.aicarcompanion.app.ui.theme.CarCompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h800dp")
class NavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val now = Instant.parse("2026-01-01T12:00:00Z")

    private fun navBar(level: ExplanationLevel, selected: String, fontScale: Float = 1f, onSelect: (NavTab) -> Unit = {}) {
        compose.setContent {
            CarCompanionTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    FloatingNavBar(LevelNavigation.tabs(level), selected, onSelect)
                }
            }
        }
    }

    @Test fun `each level renders its tabs with selected semantics`() {
        var level by mutableStateOf(ExplanationLevel.EVERYDAY)
        compose.setContent {
            CarCompanionTheme { FloatingNavBar(LevelNavigation.tabs(level), LevelNavigation.tabs(level)[1].route) {} }
        }
        ExplanationLevel.entries.forEach { l ->
            compose.runOnIdle { level = l }
            LevelNavigation.tabs(l).forEachIndexed { i, tab ->
                val node = compose.onNodeWithText(tab.label)
                node.assertIsDisplayed()
                if (i == 1) node.assertIsSelected() else node.assertIsNotSelected()
            }
        }
    }

    @Test fun `tapping a tab reports it`() {
        var picked: NavTab? = null
        navBar(ExplanationLevel.DIY, NavRoutes.HOME) { picked = it }
        compose.onNodeWithText("Drive").performClick()
        assertEquals(Destination.DRIVE, picked?.destination)
    }

    @Test fun `five tabs fit at 360dp and 200 percent text with 48dp touch targets`() {
        navBar(ExplanationLevel.ADVANCED, NavRoutes.HOME, fontScale = 2f)
        val tabNodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).fetchSemanticsNodes()
        assertEquals(5, tabNodes.size)
        val density = compose.density
        val widthDp = 360f
        var previousRight = 0f
        tabNodes.sortedBy { it.boundsInRoot.left }.forEach { n ->
            val b = n.boundsInRoot
            val w = b.width / density.density
            val h = b.height / density.density
            assertTrue("tab width $w", w >= 48f)
            assertTrue("tab height $h", h >= 48f)
            assertTrue("inside screen", b.left >= 0f && b.right / density.density <= widthDp)
            assertTrue("no overlap", b.left >= previousRight - 0.5f)
            previousRight = b.right
        }
        // Labels stay single line: tab height doesn't grow with a wrapped label.
        compose.onNodeWithText("Live Data").getBoundsInRoot().let { assertTrue(it.bottom - it.top < 120.dp) }
    }

    @Test fun `drive screen is honest and offers voice`() {
        var talked = false
        compose.setContent { CarCompanionTheme { DriveScreen("Penelope", PaddingValues(), onTalk = { talked = true }) } }
        compose.onNodeWithText("Coming in a later update").assertIsDisplayed()
        compose.onNodeWithText("Talk to Penelope").performClick()
        assertTrue(talked)
    }

    @Test fun `more screen shows level rows`() {
        var action: MoreAction? = null
        compose.setContent { CarCompanionTheme { MoreScreen(ExplanationLevel.ADVANCED, "Penelope", PaddingValues()) { action = it } } }
        compose.onNodeWithText("Chat with Penelope").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Connection").performClick()
        assertEquals(MoreAction.CONNECTION, action)
    }

    private fun live(status: LiveDataStatus, readings: List<SensorReading>, showPid: Boolean = false) {
        compose.setContent { CarCompanionTheme { LiveDataContent(status, readings, now, showPid, PaddingValues()) } }
    }

    @Test fun `live data labels sample readings`() {
        val r = listOf(SensorReading("Engine speed", 2400.0, "rpm", now.minusSeconds(3), DataOrigin.SAMPLE, "010C"))
        live(LiveDataStatus.Sample, r)
        compose.onAllNodesWithText("Sample data").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        compose.onNodeWithText("Engine speed").assertIsDisplayed()
        compose.onNodeWithText("Customizable gauges are coming next.").assertIsDisplayed()
    }

    @Test fun `live data shows pid for advanced and age`() {
        val r = listOf(SensorReading("Engine speed", 2400.0, "rpm", now.minusSeconds(3), DataOrigin.OBD_BLUETOOTH, "010C"))
        live(LiveDataStatus.Live, r, showPid = true)
        compose.onNodeWithText("PID 010C · Updated 3 s ago").assertIsDisplayed()
        compose.onNodeWithText("2400 rpm").assertIsDisplayed()
    }

    @Test fun `live data never shows values when disconnected`() {
        val status = LiveDataStatus.of(true, emptyList(), ObdConnectionState.Disconnected, now)
        live(status, emptyList())
        compose.onNodeWithText("Adapter not connected").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("rpm", substring = true).fetchSemanticsNodes().size)
    }

    @Test fun `live data hides real readings while disconnected`() {
        val r = listOf(SensorReading("Engine speed", 2400.0, "rpm", now.minusSeconds(3), DataOrigin.OBD_BLUETOOTH, "010C"))
        val status = LiveDataStatus.of(true, r, ObdConnectionState.Disconnected, now)
        live(status, r)
        assertEquals(0, compose.onAllNodesWithText("Engine speed").fetchSemanticsNodes().size)
    }

    @Test fun `live data stale banner`() {
        val ts = now.minusSeconds(30)
        val r = listOf(SensorReading("Engine speed", 2400.0, "rpm", ts, DataOrigin.OBD_BLUETOOTH, "010C"))
        live(LiveDataStatus.of(true, r, ObdConnectionState.Connected(ObdTransport.WIFI, "A"), now), r)
        compose.onNodeWithText("Stale · last updated 30 s ago").assertIsDisplayed()
    }
}
