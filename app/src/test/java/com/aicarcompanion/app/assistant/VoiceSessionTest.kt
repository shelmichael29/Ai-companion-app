@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.aicarcompanion.app.assistant

import com.aicarcompanion.app.assistant.conversation.AssistantReply
import com.aicarcompanion.app.assistant.conversation.AssistantRequest
import com.aicarcompanion.app.assistant.conversation.ConversationAction
import com.aicarcompanion.app.assistant.conversation.ConversationManager
import com.aicarcompanion.app.assistant.conversation.ReasoningProvider
import com.aicarcompanion.app.assistant.conversation.ReplyBasis
import com.aicarcompanion.app.assistant.conversation.Speaker
import com.aicarcompanion.app.assistant.voice.NoAudioFocus
import com.aicarcompanion.app.assistant.voice.SpeakResult
import com.aicarcompanion.app.assistant.voice.SpeechErrorMapper
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionTest {

    /** Fake recognizer. [micOpen] counts listening sessions currently collecting (mic on). */
    private class FakeInput : SpeechInput {
        private var channel = Channel<SpeechInputEvent>(Channel.UNLIMITED)
        var listens = 0
        var micOpen = 0
        var finishCalls = 0
        override val isAvailable = true
        override fun listen(): Flow<SpeechInputEvent> = flow {
            channel = Channel(Channel.UNLIMITED)
            listens++
            micOpen++
            try { emitAll(channel) } finally { micOpen-- }
        }
        override fun finishListening() { finishCalls++ }
        fun say(text: String) { channel.trySend(SpeechInputEvent.Final(text)); channel.close() }
        fun fail(e: SpeechInputError) { channel.trySend(SpeechInputEvent.Error(e)); channel.close() }
    }

    private class FakeOutput : SpeechOutput {
        override val state = MutableStateFlow<SpeechOutputState>(SpeechOutputState.Ready)
        val spoken = mutableListOf<String>()
        var current: CompletableDeferred<SpeakResult>? = null
        var holdSpeech = false
        var speakingNow = 0
        var overlaps = 0
        override suspend fun speak(text: String): SpeakResult {
            spoken += text
            if (++speakingNow > 1) overlaps++
            try {
                if (!holdSpeech) return SpeakResult.Completed
                return CompletableDeferred<SpeakResult>().also { current = it }.await()
            } finally { speakingNow-- }
        }
        override fun stop() { current?.complete(SpeakResult.Interrupted); current = null }
        override fun configure(config: VoiceConfig) = Unit
    }

    private val echo = object : ReasoningProvider {
        override suspend fun respond(request: AssistantRequest) = when (request.utterance) {
            "goodbye" -> AssistantReply("Okay. Talk to you later.", ReplyBasis.CONVERSATION, action = ConversationAction.END)
            "pause" -> AssistantReply("Okay, I'll pause.", ReplyBasis.CONVERSATION, action = ConversationAction.PAUSE)
            else -> AssistantReply("You said: ${request.utterance}", ReplyBasis.CONVERSATION)
        }
    }

    private fun TestScope.session(
        input: FakeInput,
        output: FakeOutput,
        continuous: Boolean = true,
        focus: NoAudioFocus = NoAudioFocus(),
    ) = VoiceSession(this, input, output, ConversationManager(echo, { "Penelope" }), focus, { continuous })

    @Test fun `continuous conversation resumes listening after each reply without a tap`() = runTest {
        val input = FakeInput(); val output = FakeOutput()
        val s = session(input, output)
        s.onMicPressed(); advanceUntilIdle()
        assertTrue(s.conversationActive.value)
        input.say("is my car ok"); advanceUntilIdle()
        assertEquals(listOf("You said: is my car ok"), output.spoken)
        assertTrue("should be listening again", s.state.value is VoiceState.Listening)
        input.say("thanks"); advanceUntilIdle()
        assertEquals(3, input.listens)
        assertEquals(4, s.conversation.turns.value.size)
        s.end(); advanceUntilIdle()
    }

    @Test fun `microphone is never open while speaking`() = runTest {
        val input = FakeInput(); val output = FakeOutput().apply { holdSpeech = true }
        val s = session(input, output)
        s.start(); advanceUntilIdle()
        input.say("hello"); advanceUntilIdle()
        assertTrue(s.state.value is VoiceState.Speaking)
        assertEquals(0, input.micOpen)
        s.end(); advanceUntilIdle()
    }

    @Test fun `single-turn mode goes idle after the reply`() = runTest {
        val input = FakeInput(); val output = FakeOutput()
        val s = session(input, output, continuous = false)
        s.onMicPressed(); advanceUntilIdle()
        assertFalse(s.conversationActive.value)
        input.say("hello"); advanceUntilIdle()
        assertEquals(VoiceState.Idle, s.state.value)
        assertEquals(1, input.listens)
        assertEquals(0, input.micOpen)
    }

    @Test fun `saying goodbye ends the conversation and turns the mic off`() = runTest {
        val input = FakeInput(); val output = FakeOutput(); val focus = NoAudioFocus()
        val s = session(input, output, focus = focus)
        s.start(); advanceUntilIdle()
        assertEquals(1, focus.held)
        input.say("goodbye"); advanceUntilIdle()
        assertEquals(VoiceState.Idle, s.state.value)
        assertFalse(s.conversationActive.value)
        assertEquals(0, input.micOpen)
        assertEquals(1, input.listens)
        assertEquals(0, focus.held)
    }

    @Test fun `saying pause pauses with the mic off`() = runTest {
        val input = FakeInput(); val output = FakeOutput()
        val s = session(input, output)
        s.start(); advanceUntilIdle()
        input.say("pause"); advanceUntilIdle()
        assertTrue(s.state.value is VoiceState.Paused)
        assertEquals(0, input.micOpen)
        s.onMicPressed(); advanceUntilIdle() // resume
        assertTrue(s.state.value is VoiceState.Listening)
        s.end(); advanceUntilIdle()
    }

    @Test fun `silence pauses instead of restarting forever`() = runTest {
        val input = FakeInput(); val output = FakeOutput()
        val s = session(input, output)
        s.start(); advanceUntilIdle()
        input.fail(SpeechInputError.NO_SPEECH); advanceUntilIdle()
        assertTrue(s.state.value is VoiceState.Listening) // one quiet retry
        input.fail(SpeechInputError.NO_SPEECH); advanceUntilIdle()
        assertTrue(s.state.value is VoiceState.Paused)
        assertFalse(s.conversationActive.value)
        assertEquals(2, input.listens)
        assertEquals(0, input.micOpen)
        assertTrue(output.spoken.last().contains("pause"))
    }

    @Test fun `recognizer busy is retried a limited number of times`() = runTest {
        val input = FakeInput(); val output = FakeOutput()
        val s = session(input, output)
        s.start(); advanceUntilIdle()
        repeat(3) { input.fail(SpeechInputError.BUSY); advanceUntilIdle() }
        assertTrue(s.state.value is VoiceState.Error)
        assertEquals(3, input.listens)
        assertEquals(0, input.micOpen)
    }

    @Test fun `network error stops the conversation with a clear message`() = runTest {
        val input = FakeInput(); val output = FakeOutput()
        val s = session(input, output)
        s.start(); advanceUntilIdle()
        input.fail(SpeechInputError.NETWORK); advanceUntilIdle()
        assertEquals(VoiceState.Error(SpeechInputError.NETWORK.userMessage), s.state.value)
        assertFalse(s.conversationActive.value)
        assertEquals(1, input.listens)
    }

    @Test fun `hearing her own words is ignored`() = runTest {
        val input = FakeInput(); val output = FakeOutput()
        val s = session(input, output)
        s.start(); advanceUntilIdle()
        input.say("check my car please"); advanceUntilIdle()
        input.say("you said check my car"); advanceUntilIdle() // echo of the reply from the speakers
        assertEquals(1, output.spoken.size)
        assertEquals(2, s.conversation.turns.value.size)
        assertTrue(s.state.value is VoiceState.Listening)
        s.end(); advanceUntilIdle()
    }

    @Test fun `tapping while speaking interrupts and keeps the conversation going`() = runTest {
        val input = FakeInput(); val output = FakeOutput().apply { holdSpeech = true }
        val s = session(input, output)
        s.start(); advanceUntilIdle()
        input.say("tell me a long story"); advanceUntilIdle()
        s.onMicPressed(); advanceUntilIdle()
        assertTrue(s.state.value is VoiceState.Listening)
        assertTrue(s.conversationActive.value)
        assertEquals(0, output.overlaps)
        s.end(); advanceUntilIdle()
    }

    @Test fun `tapping while listening finishes the utterance`() = runTest {
        val input = FakeInput(); val output = FakeOutput()
        val s = session(input, output)
        s.onMicPressed(); advanceUntilIdle()
        s.onMicPressed()
        assertEquals(1, input.finishCalls)
        s.end(); advanceUntilIdle()
    }

    @Test fun `going to the background pauses and closes the mic`() = runTest {
        val input = FakeInput(); val output = FakeOutput()
        val s = session(input, output)
        s.start(); advanceUntilIdle()
        assertEquals(1, input.micOpen)
        s.onAppBackgrounded(); advanceUntilIdle()
        assertTrue(s.state.value is VoiceState.Paused)
        assertEquals(0, input.micOpen)
        assertFalse(s.conversationActive.value)
    }

    @Test fun `losing audio focus while speaking pauses`() = runTest {
        val input = FakeInput(); val output = FakeOutput().apply { holdSpeech = true }; val focus = NoAudioFocus()
        val s = session(input, output, focus = focus)
        s.start(); advanceUntilIdle()
        input.say("hello"); advanceUntilIdle()
        focus.onFocusLost?.invoke(); advanceUntilIdle()
        assertTrue(s.state.value is VoiceState.Paused)
        assertEquals(0, input.micOpen)
        assertEquals(0, focus.held)
    }

    @Test fun `typed question continues an active conversation`() = runTest {
        val input = FakeInput(); val output = FakeOutput()
        val s = session(input, output)
        s.start(); advanceUntilIdle()
        s.submitText("hello"); advanceUntilIdle()
        assertEquals(listOf("You said: hello"), output.spoken)
        assertTrue(s.state.value is VoiceState.Listening)
        assertEquals(1, input.micOpen)
        s.end(); advanceUntilIdle()
        assertEquals(0, input.micOpen)
    }

    @Test fun `permission denial is explained and stops the conversation`() = runTest {
        val s = session(FakeInput(), FakeOutput())
        s.onPermissionDenied(permanently = true)
        val state = s.state.value as VoiceState.Error
        assertTrue(state.needsPermission)
        assertTrue(state.message.contains("Android Settings"))
        assertFalse(s.conversationActive.value)
    }

    @Test fun `speech output failure stops the loop but keeps the written reply`() = runTest {
        val output = object : SpeechOutput {
            override val state = MutableStateFlow<SpeechOutputState>(SpeechOutputState.Unavailable("no tts"))
            override suspend fun speak(text: String) = SpeakResult.Failed("no tts")
            override fun stop() = Unit
            override fun configure(config: VoiceConfig) = Unit
        }
        val input = FakeInput()
        val s = VoiceSession(this, input, output, ConversationManager(echo, { "Penelope" }))
        s.start(); advanceUntilIdle()
        input.say("hi"); advanceUntilIdle()
        assertTrue(s.state.value is VoiceState.Error)
        assertFalse(s.conversationActive.value)
        assertEquals("You said: hi", s.conversation.turns.value.last { it.speaker == Speaker.ASSISTANT }.text)
    }

    @Test fun `echo guard`() {
        assertTrue(EchoGuard.isEcho("your engine temperature is", "Your engine temperature is 194 degrees."))
        assertFalse(EchoGuard.isEcho("what is my engine temperature", "Your engine temperature is 194 degrees."))
        assertFalse(EchoGuard.isEcho("yes", "Yes, it is."))
    }

    @Test fun `android error codes map to user messages`() {
        assertEquals(SpeechInputError.NETWORK, SpeechErrorMapper.map(2))
        assertEquals(SpeechInputError.NO_MATCH, SpeechErrorMapper.map(7))
        assertEquals(SpeechInputError.PERMISSION, SpeechErrorMapper.map(9))
        assertEquals(SpeechInputError.LANGUAGE_UNAVAILABLE, SpeechErrorMapper.map(13))
        assertTrue(SpeechErrorMapper.isNetworkRelated(4))
    }
}
