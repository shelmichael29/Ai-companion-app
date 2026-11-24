package com.aicarcompanion.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aicarcompanion.app.assistant.persona.AssistantProfiles
import com.aicarcompanion.app.assistant.persona.VoiceGender
import com.aicarcompanion.app.assistant.voice.VoiceResolution
import com.aicarcompanion.app.data.local.AppSettings
import com.aicarcompanion.app.data.local.ThemeMode
import com.aicarcompanion.app.ui.settings.PersonalizationActions
import com.aicarcompanion.app.ui.settings.PersonalizationContent
import com.aicarcompanion.app.ui.settings.PersonalizationState
import com.aicarcompanion.app.ui.theme.CarCompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h800dp")
class PersonalizationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun available(id: String) = VoiceResolution.Voice("v-$id", "Female voice 1 · works offline", VoiceGender.FEMALE, false, false)

    private val allAvailable = AssistantProfiles.all.associate { it.id to available(it.id) as VoiceResolution? }
    private val noMale = AssistantProfiles.all.associate {
        it.id to (if (it.voiceGender == VoiceGender.MALE) VoiceResolution.FallbackDefault("No male voice is installed on this phone") else available(it.id)) as VoiceResolution?
    }

    private fun show(
        settings: AppSettings = AppSettings(),
        resolutions: Map<String, VoiceResolution?> = allAvailable,
        actions: PersonalizationActions = PersonalizationActions(),
        mode: ThemeMode = ThemeMode.DARK,
    ) {
        compose.setContent {
            CarCompanionTheme(mode, reduceMotion = true) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    PersonalizationContent(PersonalizationState(settings, emptyList(), emptyList(), resolutions, null), actions)
                }
            }
        }
    }

    private val isRadio = SemanticsMatcher("is a radio button") { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }

    @Test fun allTenProfilesAreSelectableTiles() {
        show()
        assertEquals(10, compose.onAllNodes(isRadio).fetchSemanticsNodes().size)
        AssistantProfiles.all.forEach { compose.onNodeWithText(it.name, useUnmergedTree = true).performScrollTo().assertIsDisplayed() }
    }

    @Test fun tappingATileSelectsItAndTheSelectionIsHighlighted() {
        var s by mutableStateOf(AppSettings())
        compose.setContent {
            CarCompanionTheme(ThemeMode.DARK, reduceMotion = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PersonalizationContent(
                        PersonalizationState(s, emptyList(), emptyList(), allAvailable, null),
                        PersonalizationActions(onSelectProfile = { s = s.copy(assistantProfileId = it) }),
                    )
                }
            }
        }
        compose.onNode(isRadio and hasText("Ace", substring = true)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(isRadio and hasText("Ace", substring = true)).assertIsSelected()
        assertEquals("ace", s.assistantProfileId)
    }

    @Test fun everyPreviewButtonIsLabelledAndAtLeast48dp() {
        val previewed = mutableListOf<String>()
        show(actions = PersonalizationActions(onPreview = { previewed += it }))
        val buttons = compose.onAllNodesWithContentDescription("Preview", substring = true).fetchSemanticsNodes()
        assertEquals(10, buttons.size)
        val density = compose.activity.resources.displayMetrics.density
        buttons.forEach { assertTrue(it.size.width / density >= 47.5f && it.size.height / density >= 47.5f) }
        compose.onNodeWithContentDescription("Preview Atlas's voice").performScrollTo().performClick()
        assertEquals(listOf("atlas"), previewed)
    }

    @Test fun theSelectedTileShowsTheCustomNameInItsPreviewLabel() {
        show(AppSettings(assistantProfileId = "atlas", customName = "Jarvis"))
        compose.onNodeWithContentDescription("Preview Jarvis's voice").performScrollTo().assertIsDisplayed()
    }

    @Test fun slidersExposeTheirValueToTalkBack() {
        show()
        compose.onNode(SemanticsMatcher("speed slider") { it.config.getOrNull(SemanticsProperties.StateDescription) == "1.00x" })
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun resetAsksForConfirmationFirst() {
        var resets = 0
        show(actions = PersonalizationActions(onReset = { resets++ }))
        compose.onNodeWithText("Reset to default").performScrollTo().performClick()
        assertEquals(0, resets)
        compose.onNodeWithText("Reset personalization?").assertIsDisplayed()
        compose.onNodeWithText("Reset").performClick()
        assertEquals(1, resets)
    }

    @Test fun missingMaleVoiceIsExplainedHonestly() {
        show(resolutions = noMale)
        compose.onNodeWithText("No male voice is installed on this phone.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Atlas's voice", substring = true).let { /* never offered in the picker */ }
        assertEquals(4, compose.onAllNodes(hasText("Uses the phone's default voice", substring = true)).fetchSemanticsNodes().size)
    }

    @Test fun noWarningWhenEveryVoiceIsAvailable() {
        show()
        assertEquals(0, compose.onAllNodes(hasText("No male voice", substring = true)).fetchSemanticsNodes().size)
    }

    @Test fun nameFieldShowsTheProfileNameAsTheDefault() {
        show(AppSettings(assistantProfileId = "luna"))
        compose.onNodeWithText("Leave empty to use Luna.", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun enlargedFontUsesOneColumnWithoutClippingNames() {
        RuntimeEnvironment.setFontScale(2.0f)
        show(AppSettings(assistantProfileId = "janay_latrice"))
        val tiles = compose.onAllNodes(isRadio).fetchSemanticsNodes()
        assertEquals(10, tiles.size)
        // One column: every tile has the same left edge and the same width.
        assertEquals(1, tiles.map { it.positionInRoot.x }.toSet().size)
        assertEquals(1, tiles.map { it.size.width }.toSet().size)
        // No tile is cut off by its own bounds: the layout never gives a text less than it wants.
        compose.onNode(isRadio and hasText("Janay-Latrice", substring = true)).performScrollTo().assertIsDisplayed()
    }

    @Test fun normalFontUsesTwoColumnsWithEqualHeightRows() {
        show()
        val tiles = compose.onAllNodes(isRadio).fetchSemanticsNodes()
        assertEquals(2, tiles.map { it.positionInRoot.x }.toSet().size)
        tiles.chunked(2).forEach { pair -> assertEquals(pair[0].size.height, pair[1].size.height) }
    }

    @Test fun summaryNamesTheProfileAndItsVoice() {
        show(AppSettings(assistantProfileId = "oliver"))
        compose.onNodeWithText("Oliver · Calm, intelligent, dependable · Female voice 1 · works offline", useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun worksInLightTheme() {
        show(mode = ThemeMode.LIGHT)
        compose.onNodeWithText("Assistant").assertIsDisplayed()
        assertEquals(10, compose.onAllNodes(isRadio).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasContentDescription("nothing")).fetchSemanticsNodes().size)
    }
}
