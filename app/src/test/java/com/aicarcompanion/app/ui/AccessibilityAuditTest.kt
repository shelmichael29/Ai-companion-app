package com.aicarcompanion.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onRoot
import com.aicarcompanion.app.ai.AiStatus
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.dashboard.DashboardKind
import com.aicarcompanion.app.dashboard.DashboardPlanner
import com.aicarcompanion.app.dashboard.DashboardSettings
import com.aicarcompanion.app.dashboard.HomeLayout
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.ThemeMode
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.ui.dashboard.ExtrasContent
import com.aicarcompanion.app.ui.dashboard.HomeLayoutEditor
import com.aicarcompanion.app.ui.home.HomeContent
import com.aicarcompanion.app.ui.home.HomeUiState
import com.aicarcompanion.app.ui.theme.CarCompanionTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Automated accessibility audit of the main screens (Robolectric, JVM):
 * - every tappable element has a TalkBack label (content description or text);
 * - every tappable element has a touch area of at least 48 × 48 dp;
 * - the Everyday dashboard still works at the largest system font size (200%).
 *
 * This complements, not replaces, a manual TalkBack pass on a real phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class AccessibilityAuditTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val car = Vehicle(
        id = "bmw", year = 2011, make = "BMW", model = "528i", trim = "F10",
        engine = EngineSpec(3.0, 6, EngineLayout.INLINE, engineCode = "N52B30"), odometerMiles = 98_000,
    )

    @Composable
    private fun Home(level: ExplanationLevel, dashboard: DashboardSettings = DashboardSettings()) {
        HomeContent(
            contentPadding = PaddingValues(), voice = VoiceState.Idle, conversationActive = false, micLevel = 0f,
            assistantName = "Penelope", greeting = "Good evening", ui = HomeUiState(vehicle = car, loaded = true), photo = null,
            onMic = {}, onPause = {}, onEnd = {}, onSuggestion = {}, onOpenAppSettings = {}, onOpenGarage = {},
            onOpenAdapter = {}, onOpenSettings = {}, onOpenResults = {}, onAddVehicle = {}, onSelectVehicle = {},
            onPhotoPicked = {}, aiStatus = AiStatus.Off,
            items = DashboardPlanner.itemsFor(level, dashboard), kind = DashboardKind.forLevel(level),
        )
    }

    /** Checks every clickable node in the merged semantics tree. */
    private fun auditClickables(screen: String) {
        compose.waitForIdle()
        val problems = mutableListOf<String>()
        val density = compose.activity.resources.displayMetrics.density
        fun visit(node: SemanticsNode) {
            if (node.config.getOrNull(SemanticsActions.OnClick) != null) {
                val label = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty() +
                    node.config.getOrNull(SemanticsProperties.Text)?.joinToString().orEmpty()
                // The element's real laid-out size (not clipped by scrolling). Compose's automatic
                // touch-area padding isn't counted, matching how Accessibility Scanner judges targets.
                val wDp = node.size.width / density
                val hDp = node.size.height / density
                val name = label.ifBlank { "<unlabeled node ${node.id}>" }
                if (label.isBlank()) problems += "$name has no TalkBack label"
                if (wDp < 47.5f || hDp < 47.5f) problems += "\"$name\" touch area is ${wDp.toInt()}×${hDp.toInt()} dp"
            }
            node.children.forEach(::visit)
        }
        visit(compose.onRoot().fetchSemanticsNode())
        assertTrue("$screen accessibility problems:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun everydayDashboardDark() {
        compose.setContent { CarCompanionTheme(ThemeMode.DARK, reduceMotion = true) { Home(ExplanationLevel.EVERYDAY) } }
        auditClickables("Everyday (dark)")
    }

    @Test fun everydayDashboardLight() {
        compose.setContent { CarCompanionTheme(ThemeMode.LIGHT, reduceMotion = true) { Home(ExplanationLevel.EVERYDAY) } }
        auditClickables("Everyday (light)")
    }

    @Test fun diyHome() {
        compose.setContent { CarCompanionTheme(reduceMotion = true) { Home(ExplanationLevel.DIY) } }
        compose.onNodeWithText("Adapter").performScrollTo().assertIsDisplayed()
        auditClickables("DIY home")
    }

    @Test fun advancedHome() {
        compose.setContent { CarCompanionTheme(reduceMotion = true) { Home(ExplanationLevel.ADVANCED) } }
        compose.onNodeWithText("Connection").performScrollTo().assertIsDisplayed()
        auditClickables("Advanced home")
    }

    @Test fun customizableLayoutHome() {
        val custom = DashboardPlanner.setLayout(DashboardSettings(), ExplanationLevel.DIY, HomeLayout.CUSTOMIZABLE)
        compose.setContent { CarCompanionTheme(reduceMotion = true) { Home(ExplanationLevel.DIY, custom) } }
        auditClickables("DIY home, Customizable layout")
    }

    @Test fun extrasScreen() {
        compose.setContent { CarCompanionTheme { ExtrasContent(ExplanationLevel.EVERYDAY, DashboardSettings(), {}, {}) } }
        auditClickables("Extras")
    }

    @Test fun homeLayoutEditor() {
        val custom = DashboardPlanner.setLayout(DashboardSettings(), ExplanationLevel.ADVANCED, HomeLayout.CUSTOMIZABLE)
        compose.setContent {
            CarCompanionTheme { Column(Modifier.verticalScroll(rememberScrollState())) { HomeLayoutEditor(ExplanationLevel.ADVANCED, custom, {}) } }
        }
        auditClickables("Home layout editor")
    }

    @Test fun everydayDashboardAtLargestFontSize() {
        RuntimeEnvironment.setFontScale(2.0f)
        compose.setContent { CarCompanionTheme(reduceMotion = true) { Home(ExplanationLevel.EVERYDAY) } }
        // Key content is still reachable and readable when scrolled to.
        compose.onNodeWithText("Good evening").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Connect my adapter").performScrollTo().assertIsDisplayed()
        auditClickables("Everyday at 200% font")
    }

    @Composable
    private fun personalizationContent() = androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
        com.aicarcompanion.app.ui.settings.PersonalizationContent(
            com.aicarcompanion.app.ui.settings.PersonalizationState(
                com.aicarcompanion.app.data.local.AppSettings(), emptyList(),
                com.aicarcompanion.app.assistant.persona.AssistantProfiles.all.associate {
                    it.id to com.aicarcompanion.app.assistant.voice.VoiceResolution.Voice("v-${it.id}", "Female voice 1 · works offline", null, false, false)
                },
                nameError = null,
            ),
            com.aicarcompanion.app.ui.settings.PersonalizationActions(),
        )
    }

    @Test fun personalizationSection() {
        compose.setContent { CarCompanionTheme(ThemeMode.DARK, reduceMotion = true) { personalizationContent() } }
        auditClickables("Personalization")
    }

    @Test fun personalizationSectionAtLargestFontSize() {
        RuntimeEnvironment.setFontScale(2.0f)
        compose.setContent { CarCompanionTheme(ThemeMode.LIGHT, reduceMotion = true) { personalizationContent() } }
        auditClickables("Personalization at 200% font")
    }

    /** Proves the audit itself works: a tiny, unlabeled button must be reported. */
    @Test fun auditCatchesSmallUnlabeledTargets() {
        compose.setContent {
            CarCompanionTheme {
                androidx.compose.foundation.layout.Box(
                    Modifier.size(20.dp).clickable { },
                )
            }
        }
        val error = runCatching { auditClickables("deliberately broken") }.exceptionOrNull()
        assertTrue("audit missed a broken target", error is AssertionError)
        assertTrue(error!!.message!!.contains("no TalkBack label"))
        assertTrue(error.message!!.contains("20×20 dp"))
    }
}
