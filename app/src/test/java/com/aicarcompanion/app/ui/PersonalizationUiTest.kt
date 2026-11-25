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
import androidx.compose.ui.test.performTextInput
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
        it.id to (if (it.voiceGender == VoiceGender.MALE) VoiceResolution.FallbackDefault("No male voice is installed on this phone", VoiceGender.MALE) else available(it.id)) as VoiceResolution?
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
                    PersonalizationContent(PersonalizationState(settings, emptyList(), resolutions, null), actions)
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
                        PersonalizationState(s, emptyList(), allAvailable, null),
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

    @Test fun tilesAlwaysShowTheProfileNameNeverTheCustomName() {
        show(AppSettings(assistantProfileId = "atlas", customName = "Jarvis"))
        compose.onNodeWithContentDescription("Preview Atlas's voice").performScrollTo().assertIsDisplayed()
        compose.onNode(isRadio and hasText("Atlas", substring = true)).assertIsSelected()
        assertEquals(0, compose.onAllNodes(isRadio and hasText("Jarvis", substring = true)).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithContentDescription("Preview Jarvis's voice").fetchSemanticsNodes().size)
        // The custom name is shown in the summary line.
        compose.onNodeWithText("Jarvis · Confident, professional", substring = true, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun theSelectedTileShowsItsOwnVoiceAndNoDropdownExists() {
        val res = allAvailable + ("atlas" to VoiceResolution.Voice("v-atlas", "Male voice 2 · works offline", VoiceGender.MALE, false, false))
        show(AppSettings(assistantProfileId = "atlas"), resolutions = res)
        val tile = compose.onNode(isRadio and hasText("Atlas", substring = true)).fetchSemanticsNode()
        val texts = tile.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
        assertTrue(texts, texts.contains("Male voice 2"))
        // The separate voice dropdown is gone: no "Same as personality" option, and the summary shows the profile's own voice.
        assertEquals(0, compose.onAllNodes(hasText("Same as personality", substring = true)).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText("'s voice ·", substring = true)).fetchSemanticsNodes().size)
        compose.onNodeWithText("More voice options").performScrollTo().assertIsDisplayed()
    }

    private val sourceRadios = SemanticsMatcher("voice source radio") {
        it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
            (it.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { t -> t.text }).let { t -> t.contains("Online (") || t.contains("Offline (") }
    }

    @Test fun voiceSourceHasTwoRadioOptionsOfAtLeast48dp() {
        val picked = mutableListOf<com.aicarcompanion.app.data.local.VoiceSource>()
        show(actions = PersonalizationActions(onVoiceSource = { picked += it }))
        val nodes = compose.onAllNodes(sourceRadios).fetchSemanticsNodes()
        assertEquals(2, nodes.size)
        val density = compose.activity.resources.displayMetrics.density
        nodes.forEach { assertTrue(it.size.height / density >= 47.5f) }
        compose.onNode(isRadio and hasText("Offline (works without internet)")).assertIsSelected()
        compose.onNode(isRadio and hasText("Online (more natural, needs internet)")).performScrollTo().performClick()
        assertEquals(listOf(com.aicarcompanion.app.data.local.VoiceSource.ONLINE), picked)
    }

    @Test fun onlineWithNoOnlineVoicesSaysSoHonestly() {
        show(AppSettings(voiceSource = com.aicarcompanion.app.data.local.VoiceSource.ONLINE))
        compose.onNodeWithText("This phone has no online voices installed; using offline voices.").performScrollTo().assertIsDisplayed()
    }

    @Test fun noNoteWhenOnlineVoicesAreUsedOrOfflineIsChosen() {
        val online = allAvailable.mapValues { (id, _) -> VoiceResolution.Voice("v-$id", "Female voice 1 · needs internet", VoiceGender.FEMALE, false, true) as VoiceResolution? }
        show(AppSettings(voiceSource = com.aicarcompanion.app.data.local.VoiceSource.ONLINE), resolutions = online)
        assertEquals(0, compose.onAllNodes(hasText("no online voices", substring = true)).fetchSemanticsNodes().size)
    }

    @Test fun offlineChoiceShowsNoOnlineNote() {
        show()
        assertEquals(0, compose.onAllNodes(hasText("no online voices", substring = true)).fetchSemanticsNodes().size)
    }

    @Test fun aProfileWhoseVoiceCouldNotBeResolvedSaysSo() {
        show(resolutions = allAvailable + ("max" to null))
        val tile = compose.onNode(isRadio and hasText("Max", substring = true)).fetchSemanticsNode()
        assertTrue(tile.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }.contains("Voice unavailable"))
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
        assertEquals(4, compose.onAllNodes(hasText("Uses the phone's default voice", substring = true)).fetchSemanticsNodes().size)
    }

    @Test fun noWarningWhenEveryVoiceIsAvailable() {
        show()
        assertEquals(0, compose.onAllNodes(hasText("No male voice", substring = true)).fetchSemanticsNodes().size)
    }

    @Test fun anEditedNameIsSavedWhenTheScreenIsLeft() {
        val saved = mutableListOf<String>()
        var visible by mutableStateOf(true)
        compose.setContent {
            CarCompanionTheme(ThemeMode.DARK, reduceMotion = true) {
                if (visible) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        PersonalizationContent(
                            PersonalizationState(AppSettings(), emptyList(), allAvailable, null),
                            PersonalizationActions(onRename = { saved += it }),
                        )
                    }
                }
            }
        }
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performScrollTo().performTextInput("Zed")
        compose.waitForIdle()
        assertEquals(emptyList<String>(), saved)
        visible = false
        compose.waitForIdle()
        assertEquals(listOf("Zed"), saved)
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
