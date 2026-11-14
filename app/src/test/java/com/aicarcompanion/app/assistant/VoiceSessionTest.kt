package com.aicarcompanion.app.assistant

import com.aicarcompanion.app.assistant.conversation.AssistantReply
import com.aicarcompanion.app.assistant.conversation.ConversationManager
import com.aicarcompanion.app.assistant.conversation.ReasoningProvider
import com.aicarcompanion.app.assistant.conversation.ReplyBasis
import com.aicarcompanion.app.assistant.conversation.Speaker
import com.aicarcompanion.app.assistant.voice.SpeakResult
import com.aicarcompanion.app.assistant.voice.SpeechErrorMapper
import com.aicarcompanion.app.assistant.voice.SpeechInput
import com.aicarcompanion.app.assistant.voice.SpeechInputError
import com.aicarcompanion.app.assistant.voice.SpeechInputEvent
import com.aicarcompanion.app.assistant.voice.SpeechOutput
import com.aicarcompanion.app.assistant.voice.SpeechOutputState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionTest {

    private class FakeInput : SpeechInput {
        var channel = Channel<SpeechInputEvent>(Channel.UNLIMITED)
        var finishCalls = 0
        override val isAvailable = true
        override fun listen(): Flow<SpeechInputEvent> { channel = Channel(Channel.UNLIMITED); return channel.receiveAsFlow() }
        override fun finishListening() { finishCalls++ }
        fun say(text: String) { channel.trySend(SpeechInputEvent.Final(text)); channel.close() }
        fun fail(e: SpeechInputError) { channel.trySend(SpeechInputEvent.Error(e)); channel.close() }
    }

    private class FakeOutput : SpeechOutput {
        override val state = MutableStateFlow<SpeechOutputState>(SpeechOutputState.Ready)
        val spoken = mutableListOf<String>()
        var current: CompletableDeferred<SpeakResult>? = null
        var holdSpeech = false
        override suspend fun speak(text: String): SpeakResult {
            spoken += text
            if (!holdSpeech) return SpeakResult.Completed
            return CompletableDeferred<SpeakResult>().also { current = it }.await()
        }
        override fun stop() { current?.complete(SpeakResult.Interrupted); current = null }
        override fun setRate(rate: Float) = Unit
    }

    private val echo = object : ReasoningProvider {
        override suspend fun respond(utterance: String, assistantName: String) =
            AssistantReply("You said: $utterance", ReplyBasis.CONVERSATION)
    }

    private fun TestScope.session(input: FakeInput, output: FakeOutput) =
        VoiceSession(this, input, output, ConversationManager(echo, { "Penelope" }))

    @Test fun `listen, transcribe, reply and speak`() = runTest {
        val input = FakeInput(); val output = FakeOutput()
        val s = session(input, output)
        s.onMicPressed(); advanceUntilIdle()
        assertTrue(s.state.value is VoiceState.Listening)
        input.say("hello"); advanceUntilIdle()
        assertEquals(listOf("You said: hello"), output.spoken)
        assertEquals(VoiceState.Idle, s.state.value)
        assertEquals(listOf(Speaker.USER, Speaker.ASSISTANT), s.conversation.turns.value.map { it.speaker })
    }

    @Test fun `tapping while listening finishes listening`() = runTest {
        val input = FakeInput(); val output = FakeOutput()
        val s = session(input, output)
        s.onMicPressed(); advanceUntilIdle()
        s.onMicPressed()
        assertEquals(1, input.finishCalls)
        s.cancel()
    }

    @Test fun `tapping while speaking interrupts and listens again`() = runTest {
        val input = FakeInput(); val output = FakeOutput().apply { holdSpeech = true }
        val s = session(input, output)
        s.submitText("tell me a long story"); advanceUntilIdle()
        assertTrue(s.state.value is VoiceState.Speaking)
        s.onMicPressed(); advanceUntilIdle()
        assertTrue(s.state.value is VoiceState.Listening)
        s.cancel()
    }

    @Test fun `stop speaking returns to idle and keeps transcript`() = runTest {
        val input = FakeInput(); val output = FakeOutput().apply { holdSpeech = true }
        val s = session(input, output)
        s.submitText("hi"); advanceUntilIdle()
        s.stopSpeaking(); advanceUntilIdle()
        assertEquals(VoiceState.Idle, s.state.value)
        assertEquals(2, s.conversation.turns.value.size)
    }

    @Test fun `recognition errors are shown and short ones spoken`() = runTest {
        val input = FakeInput(); val output = FakeOutput()
        val s = session(input, output)
        s.onMicPressed(); advanceUntilIdle()
        input.fail(SpeechInputError.NO_MATCH); advanceUntilIdle()
        assertEquals(VoiceState.Error(SpeechInputError.NO_MATCH.userMessage), s.state.value)
        assertEquals(listOf(SpeechInputError.NO_MATCH.userMessage), output.spoken)
    }

    @Test fun `permission denial is explained`() = runTest {
        val s = session(FakeInput(), FakeOutput())
        s.onPermissionDenied(permanently = true)
        val state = s.state.value as VoiceState.Error
        assertTrue(state.needsPermission)
        assertTrue(state.message.contains("Android Settings"))
    }

    @Test fun `speech output failure still leaves the written reply`() = runTest {
        val output = object : SpeechOutput {
            override val state = MutableStateFlow<SpeechOutputState>(SpeechOutputState.Unavailable("no tts"))
            override suspend fun speak(text: String) = SpeakResult.Failed("no tts")
            override fun stop() = Unit
            override fun setRate(rate: Float) = Unit
        }
        val s = VoiceSession(this, FakeInput(), output, ConversationManager(echo, { "Penelope" }))
        s.submitText("hi"); advanceUntilIdle()
        assertTrue(s.state.value is VoiceState.Error)
        assertEquals("You said: hi", s.conversation.turns.value.last().text)
    }

    @Test fun `android error codes map to user messages`() {
        assertEquals(SpeechInputError.NETWORK, SpeechErrorMapper.map(2))
        assertEquals(SpeechInputError.NO_MATCH, SpeechErrorMapper.map(7))
        assertEquals(SpeechInputError.PERMISSION, SpeechErrorMapper.map(9))
        assertEquals(SpeechInputError.LANGUAGE_UNAVAILABLE, SpeechErrorMapper.map(13))
        assertTrue(SpeechErrorMapper.isNetworkRelated(4))
    }
}
