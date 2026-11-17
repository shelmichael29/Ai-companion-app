package com.aicarcompanion.app.assistant

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice conversation resilience: speech resources are released when a conversation ends, and the
 * two-way loop recovers after errors (network loss, a failed reply) without restarting the app.
 */
class VoiceResilienceTest {

    private class FakeInput : SpeechInput {
        private var channel = Channel<SpeechInputEvent>(Channel.UNLIMITED)
        var listens = 0
        var micOpen = 0
        override val isAvailable = true
        override fun listen(): Flow<SpeechInputEvent> = flow {
            channel = Channel(Channel.UNLIMITED)
            listens++
            micOpen++
            try { emitAll(channel) } finally { micOpen-- }
        }
        override fun finishListening() = Unit
        fun say(text: String) { channel.trySend(SpeechInputEvent.Final(text)); channel.close() }
        fun fail(e: SpeechInputError) { channel.trySend(SpeechInputEvent.Error(e)); channel.close() }
    }

    private class FakeOutput : SpeechOutput {
        override val state = MutableStateFlow<SpeechOutputState>(SpeechOutputState.Ready)
        var speaking: CompletableDeferred<SpeakResult>? = null
        var hold = false
        var stops = 0
        val spoken = mutableListOf<String>()
        override suspend fun speak(text: String): SpeakResult {
            spoken += text
            if (!hold) return SpeakResult.Completed
            return CompletableDeferred<SpeakResult>().also { speaking = it }.await()
        }
        override fun stop() { stops++; speaking?.complete(SpeakResult.Interrupted); speaking = null }
        override fun configure(config: VoiceConfig) = Unit
    }

    /** Fails the first answer when [failFirst], then answers normally. */
    private class Brain(var failFirst: Boolean = false) : ReasoningProvider {
        override suspend fun respond(request: AssistantRequest): AssistantReply {
            if (failFirst) { failFirst = false; error("reasoning failed") }
            return AssistantReply("Heard: ${request.utterance}", ReplyBasis.CONVERSATION)
        }
    }

    private fun kotlinx.coroutines.test.TestScope.session(input: FakeInput, output: FakeOutput, brain: Brain = Brain()) =
        VoiceSession(this, input, output, ConversationManager(brain, { "Penelope" }), NoAudioFocus(), { true })

    @Test fun `ending while listening closes the microphone`() = runTest {
        val input = FakeInput(); val output = FakeOutput()
        val s = session(input, output)
        s.start(); advanceUntilIdle()
        assertEquals(1, input.micOpen)
        s.end(); advanceUntilIdle()
        assertEquals("microphone must be released", 0, input.micOpen)
        assertEquals(VoiceState.Idle, s.state.value)
        assertFalse(s.conversationActive.value)
    }

    @Test fun `ending while speaking stops speech and does not reopen the microphone`() = runTest {
        val input = FakeInput(); val output = FakeOutput().apply { hold = true }
        val s = session(input, output)
        s.start(); advanceUntilIdle()
        input.say("what does P0171 mean"); advanceUntilIdle()
        assertTrue(s.state.value is VoiceState.Speaking)
        s.end(); advanceUntilIdle()
        assertTrue("speech must be stopped", output.stops >= 1)
        assertEquals(0, input.micOpen)
        assertEquals(1, input.listens)
        assertEquals(VoiceState.Idle, s.state.value)
    }

    @Test fun `after a network error the next tap starts a working conversation again`() = runTest {
        val input = FakeInput(); val output = FakeOutput()
        val s = session(input, output)
        s.start(); advanceUntilIdle()
        input.fail(SpeechInputError.NETWORK); advanceUntilIdle()
        assertTrue(s.state.value is VoiceState.Error)
        assertEquals(0, input.micOpen)

        s.onMicPressed(); advanceUntilIdle()
        assertTrue(s.state.value is VoiceState.Listening)
        input.say("hello again"); advanceUntilIdle()
        assertEquals("Heard: hello again", output.spoken.last())
        assertTrue("continuous listening resumes", s.state.value is VoiceState.Listening)
        s.end(); advanceUntilIdle()
        assertEquals(0, input.micOpen)
    }

    @Test fun `a failed reply does not leave the microphone stuck open or crash the loop`() = runTest {
        val input = FakeInput(); val output = FakeOutput()
        val s = session(input, output, Brain(failFirst = true))
        s.start(); advanceUntilIdle()
        input.say("first question"); advanceUntilIdle()
        // Whatever the state, the mic isn't held open by a broken turn, and the next turn works.
        assertTrue(input.micOpen <= 1)
        if (s.state.value !is VoiceState.Listening) { s.onMicPressed(); advanceUntilIdle() }
        input.say("second question"); advanceUntilIdle()
        assertEquals("Heard: second question", output.spoken.last())
        s.end(); advanceUntilIdle()
        assertEquals(0, input.micOpen)
    }

    @Test fun `going to the background releases the microphone and speech`() = runTest {
        val input = FakeInput(); val output = FakeOutput()
        val s = session(input, output)
        s.start(); advanceUntilIdle()
        s.onAppBackgrounded(); advanceUntilIdle()
        assertEquals(0, input.micOpen)
        assertTrue(s.state.value is VoiceState.Paused)
    }
}
