@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.aicarcompanion.app.assistant

import com.aicarcompanion.app.ai.AiNotices
import com.aicarcompanion.app.assistant.conversation.AssistantReply
import com.aicarcompanion.app.assistant.conversation.AssistantRequest
import com.aicarcompanion.app.assistant.conversation.ConversationManager
import com.aicarcompanion.app.assistant.conversation.ReasoningProvider
import com.aicarcompanion.app.assistant.conversation.ReplyBasis
import com.aicarcompanion.app.assistant.voice.NoAudioFocus
import com.aicarcompanion.app.assistant.voice.SpeakResult
import com.aicarcompanion.app.assistant.voice.SpeechInput
import com.aicarcompanion.app.assistant.voice.SpeechInputError
import com.aicarcompanion.app.assistant.voice.SpeechInputEvent
import com.aicarcompanion.app.assistant.voice.SpeechOutput
import com.aicarcompanion.app.assistant.voice.SpeechOutputState
import com.aicarcompanion.app.assistant.voice.VoiceConfig
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hands-free voice through internet drops and AI failures (Phase 7). Before this phase a speech
 * network error ended the conversation and nothing resumed it; now Penelope pauses, says so once,
 * and resumes listening by herself when validated internet returns.
 */
class VoiceNetworkRecoveryTest {

    private class Input : SpeechInput {
        private var channel = Channel<SpeechInputEvent>(Channel.UNLIMITED)
        var listens = 0
        var micOpen = 0
        var maxMicOpen = 0
        override val isAvailable = true
        override fun listen(): Flow<SpeechInputEvent> = flow {
            channel = Channel(Channel.UNLIMITED)
            listens++; micOpen++; maxMicOpen = maxOf(maxMicOpen, micOpen)
            try { emitAll(channel) } finally { micOpen-- }
        }
        override fun finishListening() = Unit
        fun say(text: String) { channel.trySend(SpeechInputEvent.Final(text)); channel.close() }
        fun fail(e: SpeechInputError) { channel.trySend(SpeechInputEvent.Error(e)); channel.close() }
    }

    private class Output : SpeechOutput {
        override val state = MutableStateFlow<SpeechOutputState>(SpeechOutputState.Ready)
        val spoken = mutableListOf<String>()
        override suspend fun speak(text: String): SpeakResult { spoken += text; return SpeakResult.Completed }
        override fun stop() = Unit
        override fun configure(config: VoiceConfig) = Unit
    }

    /** Answers with a temporary-AI-failure notice first, like Penelope's provider does. */
    private class Brain : ReasoningProvider {
        var failNext = false
        override suspend fun respond(request: AssistantRequest): AssistantReply {
            val text = "Answer to ${request.utterance}"
            return if (failNext) { failNext = false; AssistantReply("${AiNotices.TEMPORARY} $text", ReplyBasis.CONVERSATION) }
            else AssistantReply(text, ReplyBasis.CONVERSATION)
        }
    }

    private val online = MutableStateFlow(true)

    private fun TestScope.session(input: Input, output: Output, brain: Brain = Brain()) =
        VoiceSession(this, input, output, ConversationManager(brain, { "Penelope" }), NoAudioFocus(), { true }, internetOnline = online)

    @Test fun `a network drop pauses once and listening resumes by itself when internet returns`() = runTest {
        val input = Input(); val output = Output()
        val s = session(input, output)
        s.start(); advanceUntilIdle()
        online.value = false
        input.fail(SpeechInputError.NETWORK); advanceUntilIdle()
        assertEquals(VoiceState.Paused(AiNotices.OFFLINE), s.state.value)
        assertEquals("microphone released while offline", 0, input.micOpen)
        assertEquals(1, output.spoken.count { it == AiNotices.OFFLINE })

        online.value = true; advanceUntilIdle()
        assertTrue("listening again without a tap", s.state.value is VoiceState.Listening)
        input.say("is my battery ok"); advanceUntilIdle()
        assertEquals("Answer to is my battery ok", output.spoken.last())
        s.end(); advanceUntilIdle()
    }

    @Test fun `a flaky network can't cause an endless resume loop or repeated announcements`() = runTest {
        val input = Input(); val output = Output()
        val s = session(input, output)
        s.start(); advanceUntilIdle()
        // Android reports online, but recognition keeps failing on the network.
        repeat(5) {
            if (s.state.value is VoiceState.Listening) input.fail(SpeechInputError.NETWORK)
            advanceTimeBy(5_000); advanceUntilIdle()
        }
        assertTrue("stops after a bounded number of automatic resumes", s.state.value is VoiceState.Error)
        assertTrue(input.listens <= 3)
        assertEquals("the offline notice is said once per outage", 1, output.spoken.count { it == AiNotices.OFFLINE })
        assertEquals(1, input.maxMicOpen)
    }

    @Test fun `tapping the mic or ending while waiting for internet cancels the automatic resume`() = runTest {
        val input = Input(); val output = Output()
        val s = session(input, output)
        s.start(); advanceUntilIdle()
        online.value = false
        input.fail(SpeechInputError.NETWORK); advanceUntilIdle()
        s.end(); advanceUntilIdle()
        online.value = true; advanceUntilIdle()
        assertEquals(VoiceState.Idle, s.state.value)
        assertEquals(1, input.listens)
    }

    @Test fun `the microphone resumes after a temporary AI failure, with one notice and no overlap`() = runTest {
        val input = Input(); val output = Output()
        val brain = Brain().apply { failNext = true }
        val s = session(input, output, brain)
        s.start(); advanceUntilIdle()
        input.say("tell me about brake pads"); advanceUntilIdle()
        assertTrue(output.spoken.last().startsWith(AiNotices.TEMPORARY))
        assertTrue("hands-free listening continues", s.state.value is VoiceState.Listening)
        input.say("and rotors"); advanceUntilIdle()
        assertEquals("Answer to and rotors", output.spoken.last())
        assertEquals(1, output.spoken.count { it.startsWith(AiNotices.TEMPORARY) })
        assertEquals("never two listening sessions at once", 1, input.maxMicOpen)
        s.end(); advanceUntilIdle()
    }

    @Test fun `without a network monitor the old behavior is kept`() = runTest {
        val input = Input(); val output = Output()
        val s = VoiceSession(this, input, output, ConversationManager(Brain(), { "Penelope" }), NoAudioFocus(), { true })
        s.start(); advanceUntilIdle()
        input.fail(SpeechInputError.NETWORK); advanceUntilIdle()
        assertTrue(s.state.value is VoiceState.Error)
    }
}
