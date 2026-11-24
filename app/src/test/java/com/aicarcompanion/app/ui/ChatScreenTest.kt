package com.aicarcompanion.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import com.aicarcompanion.app.ai.AiStatus
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.assistant.conversation.ConversationTurn
import com.aicarcompanion.app.assistant.conversation.ReplyBasis
import com.aicarcompanion.app.assistant.conversation.Speaker
import com.aicarcompanion.app.data.local.ColorStyle
import com.aicarcompanion.app.ui.assistant.AssistantContent
import com.aicarcompanion.app.ui.assistant.ChatLayout
import com.aicarcompanion.app.ui.assistant.ChatTags
import com.aicarcompanion.app.ui.theme.CarCompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * The conversation screen: introduction vs. conversation layout, its own scroll area that
 * doesn't jump while reading, compact controls, no overlap on small screens or large fonts,
 * and TalkBack labels with 48 dp targets in both color styles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h640dp")
class ChatScreenTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val turns = mutableStateOf<List<ConversationTurn>>(emptyList())
    private val voice = mutableStateOf<VoiceState>(VoiceState.Idle)

    private fun turn(id: Long, speaker: Speaker, text: String) =
        ConversationTurn(id, speaker, text, Instant.EPOCH, if (speaker == Speaker.ASSISTANT) ReplyBasis.AI_EXPLANATION else null)

    private fun conversation(n: Int) = (0 until n).map { i ->
        turn(i.toLong(), if (i % 2 == 0) Speaker.USER else Speaker.ASSISTANT, "Message $i about the car's cooling system and what to check next.")
    }

    @Composable
    private fun Screen(style: ColorStyle = ColorStyle.MIDNIGHT) {
        CarCompanionTheme(reduceMotion = true, colorStyle = style) {
            AssistantContent(
                // Same space the floating navigation takes in the app.
                contentPadding = PaddingValues(bottom = 96.dp),
                name = "Penelope", state = voice.value, turns = turns.value, conversationActive = turns.value.isNotEmpty(),
                micLevel = 0f, continuous = true, aiStatus = AiStatus.Standby("gemini-3.8-flash"),
                micAction = {}, onPause = {}, onEnd = {}, onSubmit = {}, onClear = { turns.value = emptyList() },
                onBackHome = {}, onOpenAppSettings = {},
            )
        }
    }

    // ---- 4. Introduction only before the first message ------------------------------------------

    @Test fun `introduction and suggestions show before the first message, then make room for messages`() {
        compose.setContent { Screen() }
        compose.onNodeWithTag(ChatTags.INTRO).assertExists()
        compose.onNodeWithText("Talk to me like you'd talk to a friend who knows cars.").assertIsDisplayed()
        compose.onNodeWithTag(ChatTags.VOICE_BAR).assertDoesNotExist()

        turns.value = conversation(2)
        voice.value = VoiceState.Listening()
        compose.waitForIdle()
        compose.onNodeWithTag(ChatTags.INTRO).assertDoesNotExist()
        compose.onNodeWithText("Talk to me like you'd talk to a friend who knows cars.").assertDoesNotExist()
        compose.onNodeWithTag(ChatTags.VOICE_BAR).assertIsDisplayed()
        compose.onNodeWithTag(ChatTags.MESSAGES).assertIsDisplayed()
    }

    @Test fun `clearing the transcript restores the introduction`() {
        turns.value = conversation(4)
        compose.setContent { Screen() }
        compose.onNodeWithText("Clear").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ChatTags.INTRO).assertExists()
        compose.onNodeWithTag(ChatTags.VOICE_BAR).assertDoesNotExist()
    }

    // ---- 3. Compact voice interface --------------------------------------------------------------

    @Test fun `during a conversation the orb is compact and mic, pause and end are 48 dp buttons in one bar`() {
        turns.value = conversation(2)
        voice.value = VoiceState.Listening()
        compose.setContent { Screen() }
        val density = compose.activity.resources.displayMetrics.density
        for (label in listOf("Done talking", "Pause conversation", "End conversation")) {
            val node = compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().single()
            assertEquals("$label width", 48f, node.size.width / density, 0.6f)
            assertTrue("$label is in the voice bar", within(node.boundsInRoot, tagBounds(ChatTags.VOICE_BAR)))
        }
        val bar = tagBounds(ChatTags.VOICE_BAR)
        assertTrue("the bar is compact (${bar.height / density} dp)", bar.height / density <= ChatLayout.ORB_COMPACT.value + 16)
    }

    // ---- 2. Scrolling ------------------------------------------------------------------------------

    @Test fun `new messages scroll into view while following the conversation`() {
        turns.value = conversation(20)
        compose.setContent { Screen() }
        compose.onNodeWithText("Message 19", substring = true).assertIsDisplayed()
        turns.value = turns.value + turn(20, Speaker.ASSISTANT, "Message 20 is the newest answer.")
        compose.waitForIdle()
        compose.onNodeWithText("Message 20", substring = true).assertIsDisplayed()
    }

    @Test fun `reading older messages isn't interrupted, and a button jumps to the newest`() {
        turns.value = conversation(20)
        compose.setContent { Screen() }
        repeat(6) { compose.onNodeWithTag(ChatTags.MESSAGES).performTouchInput { swipeDown() } }
        compose.waitForIdle()
        compose.onNodeWithText("Message 0", substring = true).assertIsDisplayed()

        turns.value = turns.value + turn(20, Speaker.ASSISTANT, "Message 20 is the newest answer.")
        compose.waitForIdle()
        compose.onNodeWithText("Message 0", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(ChatTags.JUMP).assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Message 20", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(ChatTags.JUMP).assertDoesNotExist()
    }

    @Test fun `the user's own message always scrolls into view`() {
        turns.value = conversation(20)
        compose.setContent { Screen() }
        repeat(6) { compose.onNodeWithTag(ChatTags.MESSAGES).performTouchInput { swipeDown() } }
        turns.value = turns.value + turn(20, Speaker.USER, "Message 20 typed by me.")
        compose.waitForIdle()
        compose.onNodeWithText("Message 20", substring = true).assertIsDisplayed()
    }

    @Test fun `scroll rules`() {
        assertTrue(ChatLayout.shouldFollow(followingLatest = true, newestIsFromUser = false))
        assertTrue(ChatLayout.shouldFollow(followingLatest = false, newestIsFromUser = true))
        assertFalse(ChatLayout.shouldFollow(followingLatest = false, newestIsFromUser = false))
        assertTrue(ChatLayout.showIntro(0))
        assertFalse(ChatLayout.showIntro(1))
    }

    // ---- 1. No overlap, any screen, large fonts ---------------------------------------------------

    @Test fun `messages, voice controls and input never overlap, even at 200 percent font`() {
        RuntimeEnvironment.setFontScale(2.0f)
        turns.value = conversation(12)
        voice.value = VoiceState.Speaking(com.aicarcompanion.app.assistant.conversation.AssistantReply("Here's a longer answer.", ReplyBasis.AI_EXPLANATION))
        compose.setContent { Screen() }
        val bar = tagBounds(ChatTags.VOICE_BAR)
        val messages = tagBounds(ChatTags.MESSAGES)
        val input = tagBounds(ChatTags.INPUT)
        assertTrue("voice bar above messages: $bar / $messages", bar.bottom <= messages.top + 0.5f)
        assertTrue("messages above input: $messages / $input", messages.bottom <= input.top + 0.5f)
        assertTrue("messages keep real space (${messages.height}px)", messages.height > 0)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val navTop = root.bottom - 96 * compose.activity.resources.displayMetrics.density
        assertTrue("input stays above the floating navigation", input.bottom <= navTop + 0.5f)
    }

    @Test fun `the introduction stays usable at 200 percent font on a small screen`() {
        RuntimeEnvironment.setFontScale(2.0f)
        compose.setContent { Screen() }
        val intro = tagBounds(ChatTags.INTRO)
        val input = tagBounds(ChatTags.INPUT)
        assertTrue("introduction scrolls in its own area above the input", intro.bottom <= input.top + 0.5f)
        compose.onAllNodesWithContentDescription("Talk to Penelope").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
    }

    // ---- Accessibility in both color styles ---------------------------------------------------------

    @Test fun `every control has a TalkBack label and a 48 dp target, in Midnight and Amethyst`() {
        val style = mutableStateOf(ColorStyle.MIDNIGHT)
        compose.setContent { Screen(style.value) }
        for (s in ColorStyle.entries) {
            style.value = s
            turns.value = emptyList(); voice.value = VoiceState.Idle
            audit("$s introduction")
            turns.value = conversation(6); voice.value = VoiceState.Listening()
            audit("$s conversation")
        }
    }

    @Test fun `the compact orb isn't announced twice`() {
        turns.value = conversation(2)
        voice.value = VoiceState.Listening()
        compose.setContent { Screen() }
        assertEquals(1, compose.onAllNodesWithContentDescription("Done talking").fetchSemanticsNodes().size)
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private fun tagBounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun within(inner: Rect, outer: Rect) =
        inner.left >= outer.left - 0.5f && inner.right <= outer.right + 0.5f && inner.top >= outer.top - 0.5f && inner.bottom <= outer.bottom + 0.5f

    private fun audit(screen: String) {
        compose.waitForIdle()
        val problems = mutableListOf<String>()
        val density = compose.activity.resources.displayMetrics.density
        fun visit(node: SemanticsNode) {
            if (node.config.getOrNull(SemanticsActions.OnClick) != null) {
                val label = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty() +
                    node.config.getOrNull(SemanticsProperties.Text)?.joinToString().orEmpty()
                if (label.isBlank()) problems += "unlabeled node ${node.id}"
                if (node.size.width / density < 47.5f || node.size.height / density < 47.5f) {
                    problems += "\"$label\" is ${(node.size.width / density).toInt()}×${(node.size.height / density).toInt()} dp"
                }
            }
            node.children.forEach(::visit)
        }
        visit(compose.onRoot().fetchSemanticsNode())
        assertTrue("$screen:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
