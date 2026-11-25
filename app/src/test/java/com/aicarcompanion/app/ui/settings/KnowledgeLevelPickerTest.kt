package com.aicarcompanion.app.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.ui.theme.CarCompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h800dp")
class KnowledgeLevelPickerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val radio = SemanticsMatcher("is a radio button") { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }

    private fun show(selected: ExplanationLevel, fontScale: Float = 1f, onSelect: (ExplanationLevel) -> Unit = {}) {
        compose.setContent {
            CarCompanionTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    KnowledgeLevelPicker(selected, onSelect)
                }
            }
        }
    }

    @Test fun `exactly two levels are offered and Advanced is not`() {
        show(ExplanationLevel.EVERYDAY)
        compose.onAllNodes(radio).assertCountEquals(2)
        compose.onAllNodesWithText("Advanced", substring = true, ignoreCase = true).assertCountEquals(0)
        compose.onAllNodesWithText("professional", substring = true, ignoreCase = true).assertCountEquals(0)
        compose.onAllNodesWithText("Everyday driver", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("DIY enthusiast", substring = true).assertCountEquals(1)
    }

    @Test fun `a stored advanced level is shown as DIY selected`() {
        show(ExplanationLevel.ADVANCED)
        val selected = compose.onAllNodes(radio and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).fetchSemanticsNodes()
        assertEquals(1, selected.size)
        compose.onAllNodes(radio and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true) and hasTextDescendant("DIY enthusiast"))
            .assertCountEquals(1)
    }

    private fun hasTextDescendant(text: String) = SemanticsMatcher("has text $text") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(text) } == true
    }

    @Test fun `tapping a row reports that level`() {
        val picked = mutableListOf<ExplanationLevel>()
        show(ExplanationLevel.EVERYDAY) { picked += it }
        compose.onAllNodesWithText("DIY enthusiast", substring = true).onFirst().performClick()
        compose.onAllNodesWithText("Everyday driver", substring = true).onFirst().performClick()
        assertEquals(listOf(ExplanationLevel.DIY, ExplanationLevel.EVERYDAY), picked)
    }

    @Test fun `each row is one merged accessible node with its label and description`() {
        show(ExplanationLevel.EVERYDAY)
        val nodes = compose.onAllNodes(radio).fetchSemanticsNodes()
        assertEquals(2, nodes.size)
        nodes.forEach { n ->
            val text = n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
            assertTrue(text, text.contains("enthusiast", true) || text.contains("driver", true))
            assertTrue("description merged: $text", text.length > 30)
        }
    }

    @Test fun `rows stay at least 56dp tall at font scale 2`() {
        show(ExplanationLevel.DIY, fontScale = 2f)
        val boxes = (0 until 2).map { compose.onAllNodes(radio)[it].getBoundsInRoot() }
        boxes.forEach { assertTrue("${it.bottom - it.top}", it.bottom - it.top >= 56.dp) }
        assertTrue("rows must not overlap", boxes[0].bottom <= boxes[1].top)
    }
}
