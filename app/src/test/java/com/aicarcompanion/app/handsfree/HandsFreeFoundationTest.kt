package com.aicarcompanion.app.handsfree

import android.media.AudioDeviceInfo
import com.aicarcompanion.app.assistant.VoiceSession
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.assistant.conversation.AssistantReply
import com.aicarcompanion.app.assistant.conversation.AssistantRequest
import com.aicarcompanion.app.assistant.conversation.ConversationManager
import com.aicarcompanion.app.assistant.conversation.ReasoningProvider
import com.aicarcompanion.app.assistant.conversation.ReplyBasis
import com.aicarcompanion.app.assistant.voice.NoAudioFocus
import com.aicarcompanion.app.assistant.voice.SpeakResult
import com.aicarcompanion.app.assistant.voice.SpeechInput
import com.aicarcompanion.app.assistant.voice.SpeechInputEvent
import com.aicarcompanion.app.assistant.voice.SpeechOutput
import com.aicarcompanion.app.assistant.voice.SpeechOutputState
import com.aicarcompanion.app.assistant.voice.VoiceConfig
import com.aicarcompanion.app.connectivity.ConnectionLog
import com.aicarcompanion.app.connectivity.LogArea
import com.aicarcompanion.app.connectivity.VoiceDiagnostics
import com.aicarcompanion.app.handsfree.android.AndroidHandsFreeCapabilities
import com.aicarcompanion.app.handsfree.android.AudioRouteRules
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/** Phase H0: hands-free foundation and diagnostics, with fakes only (no phone calls, messages or media). */
class HandsFreeFoundationTest {

    // ---- Command routing -------------------------------------------------------------------------

    @Test fun `clear hands-free commands are recognized`() {
        val cases = mapOf(
            "play some music" to HandsFreeCommand.Media(MediaAction.PLAY),
            "Penelope, play my playlist" to HandsFreeCommand.Media(MediaAction.PLAY),
            "pause the music" to HandsFreeCommand.Media(MediaAction.PAUSE),
            "stop the podcast" to HandsFreeCommand.Media(MediaAction.PAUSE),
            "resume the music" to HandsFreeCommand.Media(MediaAction.PLAY),
            "skip this song" to HandsFreeCommand.Media(MediaAction.NEXT),
            "next track" to HandsFreeCommand.Media(MediaAction.NEXT),
            "previous song" to HandsFreeCommand.Media(MediaAction.PREVIOUS),
            "read my messages" to HandsFreeCommand.ReadMessages,
            "do I have any new texts?" to HandsFreeCommand.ReadMessages,
            "text Sarah saying I'm running late" to HandsFreeCommand.SendMessage("Sarah", "I'm running late"),
            "send a message to Mom" to HandsFreeCommand.SendMessage("Mom"),
            "can you call Mom" to HandsFreeCommand.PlaceCall("Mom"),
            "answer the call" to HandsFreeCommand.CallControl(HandsFreeCommand.CallControl.Action.ANSWER),
            "decline the call" to HandsFreeCommand.CallControl(HandsFreeCommand.CallControl.Action.DECLINE),
            "hang up" to HandsFreeCommand.CallControl(HandsFreeCommand.CallControl.Action.HANG_UP),
            "navigate to the nearest gas station" to HandsFreeCommand.Navigate,
        )
        cases.forEach { (said, expected) -> assertEquals(said, expected, VoiceCommandRouter.route(said)) }
    }

    @Test fun `conversation controls, vehicle questions and how-to questions are not intercepted`() {
        listOf(
            "pause", "stop", "goodbye", "that's all", "hold on",
            "how do I play music through my car", "what do you call this warning light",
            "why does my car shake when I play loud music", "check engine light is on",
            "is my coolant temperature ok", "read my trouble codes", "what does P0171 mean",
            "should I call a mechanic", "can I drive with a misfire", "my battery light is on",
        ).forEach { said -> assertNull(said, VoiceCommandRouter.route(said)) }
    }

    // ---- Routing in the assistant ----------------------------------------------------------------

    private class RecordingDelegate : ReasoningProvider {
        val seen = mutableListOf<String>()
        override suspend fun respond(request: AssistantRequest): AssistantReply {
            seen += request.utterance
            return AssistantReply("vehicle answer", ReplyBasis.GENERAL_KNOWLEDGE)
        }
    }

    @Test fun `hands-free commands get an honest reply and never reach the AI`() = runTest {
        val delegate = RecordingDelegate()
        val routed = mutableListOf<Pair<String, String>>()
        val p = HandsFreeReasoningProvider(delegate, AndroidHandsFreeCapabilities()) { k, s -> routed += k to s }
        val reply = p.respond(AssistantRequest("answer the call", "Penelope"))
        assertTrue(delegate.seen.isEmpty())
        assertTrue(reply.text.startsWith("I can't answer phone calls yet."))
        assertEquals(listOf("call_answer" to "NotImplemented"), routed)
    }

    @Test fun `everything else goes to the vehicle assistant unchanged`() = runTest {
        val delegate = RecordingDelegate()
        val p = HandsFreeReasoningProvider(delegate, AndroidHandsFreeCapabilities())
        assertEquals("vehicle answer", p.respond(AssistantRequest("what does P0171 mean", "Penelope")).text)
        assertEquals(listOf("what does P0171 mean"), delegate.seen)
    }

    @Test fun `each capability reports its real status, with and without notification access`() {
        val setup = CapabilityStatus.NeedsSetup(listOf(PlatformRequirement.NOTIFICATION_ACCESS))
        val off = AndroidHandsFreeCapabilities(notificationAccess = { false })
        val on = AndroidHandsFreeCapabilities(notificationAccess = { true })
        val table = mapOf(
            HandsFreeCapability.MEDIA_CONTROL to (CapabilityStatus.Available to CapabilityStatus.Available),
            HandsFreeCapability.READ_MESSAGES to (setup to CapabilityStatus.Available),
            HandsFreeCapability.SEND_MESSAGES to (setup to CapabilityStatus.Available),
            HandsFreeCapability.PLACE_CALLS to (CapabilityStatus.Available to CapabilityStatus.Available),
            HandsFreeCapability.ANSWER_CALLS to (CapabilityStatus.NotImplemented to CapabilityStatus.NotImplemented),
            HandsFreeCapability.NAVIGATION to (CapabilityStatus.NotImplemented to CapabilityStatus.NotImplemented),
            HandsFreeCapability.VEHICLE_CONTROLS to (CapabilityStatus.NotSupported to CapabilityStatus.NotSupported),
        )
        assertEquals("every capability is in the table", HandsFreeCapability.entries.toSet(), table.keys)
        table.forEach { (capability, expected) ->
            assertEquals("$capability without access", expected.first, off.status(capability))
            assertEquals("$capability with access", expected.second, on.status(capability))
        }
    }

    // ---- Safety ----------------------------------------------------------------------------------

    @Test fun `messages and calls always need confirmation and are never shared with the AI`() {
        assertTrue(HandsFreeSafety.requiresConfirmation(HandsFreeCommand.SendMessage("Mom")))
        assertTrue(HandsFreeSafety.requiresConfirmation(HandsFreeCommand.PlaceCall("Mom")))
        assertFalse(HandsFreeSafety.requiresConfirmation(HandsFreeCommand.Media(MediaAction.PAUSE)))
        assertFalse(HandsFreeSafety.mayShareWithAi(HandsFreeCommand.ReadMessages))
    }

    @Test fun `unavailable replies explain setup without technical words`() {
        val setup = HandsFreeSafety.unavailableReply(
            HandsFreeCapability.READ_MESSAGES,
            CapabilityStatus.NeedsSetup(listOf(PlatformRequirement.NOTIFICATION_ACCESS)),
        )
        assertTrue(setup.contains("notification access"))
        assertTrue(HandsFreeSafety.unavailableReply(HandsFreeCapability.PLACE_CALLS, CapabilityStatus.NotSupported).contains("doesn't let me"))
    }

    // ---- Platform separation (future iOS) ----------------------------------------------------------

    @Test fun `the hands-free core has no Android dependencies`() {
        val root = "src/main/java/com/aicarcompanion/app"
        val core = File("$root/handsfree").listFiles { f -> f.isFile && f.extension == "kt" }.orEmpty()
        val vehicle = File("$root/vehiclecontrol").listFiles { f -> f.isFile && f.extension == "kt" }.orEmpty()
        val files = core.toList() + vehicle.toList() + File("$root/ui/drive/DriveState.kt")
        assertTrue("core files found", core.size >= 4)
        assertTrue("vehicle-control files found", vehicle.isNotEmpty())
        files.forEach { f -> assertFalse("${f.name} imports Android", f.readText().contains(Regex("^import android\\.", RegexOption.MULTILINE))) }
    }

    // ---- Diagnostics -----------------------------------------------------------------------------

    @Test fun `voice diagnostics record states but never what was said`() = runTest {
        val log = ConnectionLog(now = { Instant.EPOCH })
        val states = MutableStateFlow<VoiceState>(VoiceState.Idle)
        VoiceDiagnostics(backgroundScope, log).watch(states)
        runCurrent()
        listOf(
            VoiceState.Listening("call my doctor about the"),
            VoiceState.Listening("call my doctor about the results"),
            VoiceState.Processing("call my doctor about the results"),
            VoiceState.Speaking(AssistantReply("Your private reply text", ReplyBasis.CONVERSATION)),
            VoiceState.Error("Microphone permission is off.", needsPermission = true),
        ).forEach { states.value = it; runCurrent() }
        val text = log.export()
        assertFalse(text.contains("doctor"))
        assertFalse(text.contains("private reply"))
        assertEquals(
            listOf("Voice idle", "Voice listening", "Voice thinking", "Voice speaking", "Voice error"),
            log.events.value.filter { it.area == LogArea.VOICE }.map { it.event },
        )
        assertEquals("true", log.events.value.last().details["needsPermission"])
    }

    @Test fun `audio route is chosen from output types without device names`() {
        assertEquals(AudioRoute.CAR_BLUETOOTH_MEDIA, AudioRouteRules.routeFor(setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
        assertEquals(AudioRoute.CAR_BLUETOOTH_HANDS_FREE, AudioRouteRules.routeFor(setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
        assertEquals(AudioRoute.WIRED_OR_USB, AudioRouteRules.routeFor(setOf(AudioDeviceInfo.TYPE_USB_HEADSET)))
        assertEquals(AudioRoute.PHONE_SPEAKER, AudioRouteRules.routeFor(setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
        assertEquals(AudioRoute.UNKNOWN, AudioRouteRules.routeFor(emptySet()))
    }

    // ---- Voice conversation still continuous -------------------------------------------------------

    private class Input : SpeechInput {
        private var channel = Channel<SpeechInputEvent>(Channel.UNLIMITED)
        var listens = 0
        override val isAvailable = true
        override fun listen(): Flow<SpeechInputEvent> = flow { channel = Channel(Channel.UNLIMITED); listens++; emitAll(channel) }
        override fun finishListening() = Unit
        fun say(text: String) { channel.trySend(SpeechInputEvent.Final(text)); channel.close() }
    }

    private class Output : SpeechOutput {
        override val state = MutableStateFlow<SpeechOutputState>(SpeechOutputState.Ready)
        val spoken = mutableListOf<String>()
        override suspend fun speak(text: String): SpeakResult { spoken += text; return SpeakResult.Completed }
        override fun stop() = Unit
        override fun configure(config: VoiceConfig) = Unit
    }

    @Test fun `a hands-free request mid-conversation is answered and Penelope keeps listening`() = runTest {
        val input = Input(); val output = Output(); val delegate = RecordingDelegate()
        val provider = HandsFreeReasoningProvider(delegate, AndroidHandsFreeCapabilities())
        val s = VoiceSession(this, input, output, ConversationManager(provider, { "Penelope" }), NoAudioFocus(), { true })
        s.start(); advanceUntilIdle()
        input.say("answer the call"); advanceUntilIdle()
        assertTrue(output.spoken.single().startsWith("I can't answer phone calls yet."))
        assertTrue("continuous conversation continues", s.state.value is VoiceState.Listening)
        input.say("is my coolant ok"); advanceUntilIdle()
        assertEquals("vehicle answer", output.spoken.last())
        s.end(); advanceUntilIdle()
    }
}
