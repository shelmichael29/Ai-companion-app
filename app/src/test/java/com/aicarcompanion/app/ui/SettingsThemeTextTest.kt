package com.aicarcompanion.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import com.aicarcompanion.app.ai.AiStatus
import com.aicarcompanion.app.assistant.VoiceSession
import com.aicarcompanion.app.assistant.conversation.AssistantReply
import com.aicarcompanion.app.assistant.conversation.AssistantRequest
import com.aicarcompanion.app.assistant.conversation.ConversationManager
import com.aicarcompanion.app.assistant.conversation.ReasoningProvider
import com.aicarcompanion.app.assistant.conversation.ReplyBasis
import com.aicarcompanion.app.assistant.voice.SpeakResult
import com.aicarcompanion.app.assistant.voice.SpeechInput
import com.aicarcompanion.app.assistant.voice.SpeechInputEvent
import com.aicarcompanion.app.assistant.voice.SpeechOutput
import com.aicarcompanion.app.assistant.voice.SpeechOutputState
import com.aicarcompanion.app.assistant.voice.VoiceConfig
import com.aicarcompanion.app.data.local.InMemorySettingsStore
import com.aicarcompanion.app.data.local.ThemeMode
import com.aicarcompanion.app.ui.settings.SettingsScreen
import com.aicarcompanion.app.ui.settings.SettingsViewModel
import com.aicarcompanion.app.ui.theme.CarCompanionTheme
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders the real Settings screen in dark and light mode and checks the colors the text is
 * actually drawn with. Before the fix, body text fell back to black in dark mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SettingsThemeTextTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private object SilentInput : SpeechInput {
        override val isAvailable = false
        override fun listen(): Flow<SpeechInputEvent> = emptyFlow()
        override fun finishListening() = Unit
    }

    private object SilentOutput : SpeechOutput {
        override val state = MutableStateFlow<SpeechOutputState>(SpeechOutputState.Ready)
        override suspend fun speak(text: String) = SpeakResult.Completed
        override fun stop() = Unit
        override fun configure(config: VoiceConfig) = Unit
    }

    private val reasoning = object : ReasoningProvider {
        override suspend fun respond(request: AssistantRequest) = AssistantReply("ok", ReplyBasis.CONVERSATION)
    }

    private fun viewModel() = SettingsViewModel(
        InMemorySettingsStore(),
        SilentOutput,
        VoiceSession(MainScope(), SilentInput, SilentOutput, ConversationManager(reasoning, { "Penelope" })),
        MutableStateFlow<AiStatus>(AiStatus.Off),
        "test-model",
        "none",
    )

    /** The color a Text node is drawn with, read from its laid-out style. */
    private fun drawnColor(text: String, substring: Boolean = false): Color {
        val node = compose.onNodeWithText(text, substring = substring, useUnmergedTree = true).performScrollTo().fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single().layoutInput.style.color
    }

    private fun contrast(fg: Color, bg: Color): Double {
        val a = fg.luminance() + 0.05
        val b = bg.luminance() + 0.05
        return maxOf(a, b) / minOf(a, b)
    }

    @Test fun settingsTextFollowsTheThemeAndStaysReadable() {
        var mode by mutableStateOf(ThemeMode.DARK)
        lateinit var scheme: ColorScheme
        val vm = viewModel()
        compose.setContent {
            CarCompanionTheme(themeMode = mode) {
                scheme = MaterialTheme.colorScheme
                SettingsScreen(onBack = {}, viewModel = vm)
            }
        }

        for (m in listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.DARK)) {
            mode = m
            compose.waitForIdle()
            val dark = m == ThemeMode.DARK

            // Titles and labels with no color of their own: the theme's main text color.
            for (title in listOf("Reduce motion", "Use Gemini for conversations", "Status: ", "Speaking speed: ")) {
                val c = drawnColor(title, substring = true)
                assertEquals("[$m] \"$title\"", scheme.onSurface, c)
            }
            // Descriptions: the theme's secondary text color.
            for (desc in listOf("Keeps the voice orb and background still", "Model: test-model")) {
                assertEquals("[$m] \"$desc\"", scheme.onSurfaceVariant, drawnColor(desc, substring = true))
            }

            // Light text in dark mode, dark text in light mode, with WCAG AA contrast on the screen background.
            val main = drawnColor("Reduce motion")
            val secondary = drawnColor("Keeps the voice orb and background still", substring = true)
            assertTrue("[$m] main text should be ${if (dark) "light" else "dark"}", (main.luminance() > 0.5f) == dark)
            assertTrue("[$m] never black in dark mode", !dark || main != Color.Black)
            for (bg in listOf(scheme.background, scheme.surface)) {
                assertTrue("[$m] main text contrast ${contrast(main, bg)}", contrast(main, bg) >= 4.5)
                assertTrue("[$m] secondary text contrast ${contrast(secondary, bg)}", contrast(secondary, bg) >= 4.5)
            }
        }
    }
}
