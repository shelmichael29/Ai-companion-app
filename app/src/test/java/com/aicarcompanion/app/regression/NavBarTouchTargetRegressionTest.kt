package com.aicarcompanion.app.regression

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.ui.navigation.FloatingNavBar
import com.aicarcompanion.app.ui.navigation.LevelNavigation
import com.aicarcompanion.app.ui.navigation.NavRoutes
import com.aicarcompanion.app.ui.theme.CarCompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1D (2026-11-26): on a narrow display at a large font size, the DIY floating navigation's
 * unselected tabs were 42 dp wide, below the 48 dp minimum touch target. Above 130 % font only the
 * selected tab shows its label and it took a fixed 2.8 shares of the width, which left four 42 dp tabs
 * on a 320 dp display (and 45 dp on a 344 dp one). The selected tab now gives up width first, so every
 * tab keeps 48 dp wherever that fits, and nothing changes where it already fitted (360 dp and wider).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w480dp-h900dp-xhdpi")
class NavBarTouchTargetRegressionTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** Tab widths in dp, in order, for [level] at [widthDp] and [fontScale], with Home selected. */
    private fun tabWidths(level: ExplanationLevel, widthDp: Int, fontScale: Float): Pair<List<Float>, List<Float>> {
        compose.setContent {
            CarCompanionTheme(reduceMotion = true) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Box(Modifier.requiredWidth(widthDp.dp)) {
                        FloatingNavBar(LevelNavigation.tabs(level), NavRoutes.HOME) {}
                    }
                }
            }
        }
        compose.waitForIdle()
        val density = compose.activity.resources.displayMetrics.density
        val tabs = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).fetchSemanticsNodes()
            .sortedBy { it.boundsInRoot.left }
        assertEquals(LevelNavigation.tabs(level).size, tabs.size)
        return tabs.map { it.size.width / density } to tabs.map { it.size.height / density }
    }

    private fun assertAllAtLeast48(level: ExplanationLevel, widthDp: Int, fontScale: Float) {
        val (widths, heights) = tabWidths(level, widthDp, fontScale)
        assertTrue("$level at $widthDp dp, font $fontScale: tab widths $widths", widths.all { it >= 47.5f })
        assertTrue("$level at $widthDp dp, font $fontScale: tab heights $heights", heights.all { it >= 47.5f })
    }

    @Test fun `five DIY tabs keep 48 dp on a 320 dp display at 200 percent font`() =
        assertAllAtLeast48(ExplanationLevel.DIY, 320, 2f)

    @Test fun `five DIY tabs keep 48 dp on a 344 dp cover display at 150 percent font`() =
        assertAllAtLeast48(ExplanationLevel.DIY, 344, 1.5f)

    @Test fun `Everyday tabs keep 48 dp on a 320 dp display at 200 percent font`() =
        assertAllAtLeast48(ExplanationLevel.EVERYDAY, 320, 2f)

    @Test fun `where the tabs already fitted the selected tab keeps its wide label space`() {
        // 412 dp at 200 %: the selected tab is still 2.8 times as wide as the others (unchanged look).
        val (widths, _) = tabWidths(ExplanationLevel.DIY, 412, 2f)
        val selected = widths.first()
        widths.drop(1).forEach { assertEquals(2.8f, selected / it, 0.05f) }
    }

    @Test fun `at normal font sizes all tabs are equal`() {
        val (widths, _) = tabWidths(ExplanationLevel.DIY, 320, 1f)
        widths.forEach { assertEquals(widths.first(), it, 0.5f) }
        assertTrue(widths.all { it >= 47.5f })
    }
}
