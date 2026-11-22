package com.aicarcompanion.app.ui.drive

import com.aicarcompanion.app.assistant.VoiceSession
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
import com.aicarcompanion.app.handsfree.CallTarget
import com.aicarcompanion.app.handsfree.FakeCalls
import com.aicarcompanion.app.handsfree.FakeMedia
import com.aicarcompanion.app.handsfree.FakeMessaging
import com.aicarcompanion.app.handsfree.HandsFreeDialog
import com.aicarcompanion.app.handsfree.IncomingMessage
import com.aicarcompanion.app.handsfree.MediaAction
import com.aicarcompanion.app.handsfree.MediaSource
import com.aicarcompanion.app.handsfree.NowPlaying
import com.aicarcompanion.app.handsfree.PendingAction
import com.aicarcompanion.app.handsfree.VolumeDirection
import com.aicarcompanion.app.handsfree.android.NotificationAccessState
import com.aicarcompanion.app.vehiclecontrol.NoVehicleControlProvider
import com.aicarcompanion.app.vehiclecontrol.UnavailableReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DriveViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class Input : SpeechInput {
        override val isAvailable = true
        override fun listen(): Flow<SpeechInputEvent> = emptyFlow()
        override fun finishListening() = Unit
    }

    private class Output : SpeechOutput {
        override val state = MutableStateFlow<SpeechOutputState>(SpeechOutputState.Ready)
        override suspend fun speak(text: String): SpeakResult = SpeakResult.Completed
        override fun stop() = Unit
        override fun configure(config: VoiceConfig) = Unit
    }

    private class Access(initial: Boolean) : NotificationAccessState {
        override val granted = MutableStateFlow(initial)
        var refreshes = 0
        override fun refresh() { refreshes++ }
    }

    private val message = IncomingMessage("k1", "Sarah", "Dinner at seven?", "Messages", canReply = true)

    private class Rig(scope: TestScope, access: Boolean, messages: List<IncomingMessage>, hasVehicle: Boolean = true) {
        val media = FakeMedia()
        val messaging = FakeMessaging(messages)
        val calls = FakeCalls()
        val dialog = HandsFreeDialog(messaging, calls)
        val access = Access(access)
        val spoken = mutableListOf<String>()
        val session = VoiceSession(
            scope, Input(), Output(),
            ConversationManager(object : ReasoningProvider {
                override suspend fun respond(request: AssistantRequest) = AssistantReply("ok", ReplyBasis.CONVERSATION)
            }, { "Penelope" }),
            NoAudioFocus(), { true },
        )
        val vm = DriveViewModel(media, messaging, dialog, this.access, NoVehicleControlProvider, session, flowOf(null), flowOf(hasVehicle), speak = { spoken += it })
    }

    private fun TestScope.rig(access: Boolean = true, messages: List<IncomingMessage> = listOf(message)) = Rig(this, access, messages)

    private fun TestScope.collect(rig: Rig) = backgroundScope.launch { rig.vm.state.collect {} }

    @Test fun `state reflects media, messages, calls and the unavailable car controls`() = runTest(dispatcher) {
        val r = rig()
        r.media.source.value = MediaSource.Session("Sample Music")
        r.media.nowPlaying.value = NowPlaying("Sample Music", "Song", "Artist", true)
        collect(r); advanceUntilIdle()
        val s = r.vm.state.value
        assertEquals(MediaSource.Session("Sample Music"), s.media.source)
        assertEquals("Song", s.media.nowPlaying?.title)
        assertEquals(MessagesUiState.Unread(1, "Sarah", "Messages"), s.messages)
        assertEquals(CallsUiState.DIAL_HANDOFF, s.calls)
        assertTrue(s.vehicleControls.isNotEmpty())
        assertTrue(s.vehicleControls.all { it.reason == UnavailableReason.OBD_ADAPTER_READ_ONLY })
    }

    @Test fun `without notification access messages need setup and no names are shown`() = runTest(dispatcher) {
        val r = rig(access = false)
        collect(r); advanceUntilIdle()
        assertEquals(MessagesUiState.NeedsAccess, r.vm.state.value.messages)
    }

    @Test fun `access turning on updates the messages card`() = runTest(dispatcher) {
        val r = rig(access = false, messages = emptyList())
        collect(r); advanceUntilIdle()
        r.access.granted.value = true
        advanceUntilIdle()
        assertEquals(MessagesUiState.None, r.vm.state.value.messages)
    }

    @Test fun `media buttons ask the media app and the notice says so`() = runTest(dispatcher) {
        val r = rig()
        collect(r); advanceUntilIdle()
        r.vm.pause(); advanceUntilIdle()
        assertEquals("I asked your media app to pause.", r.vm.state.value.notice)
        r.vm.next(); r.vm.previous(); r.vm.play(); advanceUntilIdle()
        assertEquals(listOf(MediaAction.PAUSE, MediaAction.NEXT, MediaAction.PREVIOUS, MediaAction.PLAY), r.media.performed)
    }

    @Test fun `volume buttons adjust the volume and a fixed volume is reported`() = runTest(dispatcher) {
        val r = rig()
        collect(r); advanceUntilIdle()
        r.vm.volume(VolumeDirection.UP); advanceUntilIdle()
        assertEquals(listOf(VolumeDirection.UP), r.media.volumeChanges)
        r.media.volume.value = null
        r.vm.volume(VolumeDirection.DOWN); advanceUntilIdle()
        assertEquals("Volume is set by your car.", r.vm.state.value.notice)
        assertNull(r.vm.state.value.media.volume)
    }

    @Test fun `picking a contact starts the shared confirmation and speaks it, but dials nothing`() = runTest(dispatcher) {
        val r = rig()
        collect(r); advanceUntilIdle()
        r.vm.onContactPicked(CallTarget("Sam Sample", "5550100")); advanceUntilIdle()
        assertTrue(r.vm.state.value.pending is PendingAction.ConfirmDial)
        assertTrue(r.spoken.single().contains("5 5 5 0 1 0 0"))
        assertTrue(r.calls.dialed.isEmpty())
        r.vm.confirm(); advanceUntilIdle()
        assertEquals(listOf(FakeCalls.Dialed("5550100", true)), r.calls.dialed)
        assertNull(r.vm.state.value.pending)
        r.vm.confirm(); advanceUntilIdle()
        assertEquals(1, r.calls.dialed.size)
    }

    @Test fun `cancel clears the confirmation and sends nothing`() = runTest(dispatcher) {
        val r = rig()
        collect(r); advanceUntilIdle()
        r.dialog.proposeReply(message, "Running late")
        advanceUntilIdle()
        assertTrue(r.vm.state.value.pending is PendingAction.ConfirmReply)
        r.vm.cancel(); advanceUntilIdle()
        assertNull(r.vm.state.value.pending)
        assertTrue(r.messaging.sent.isEmpty())
        assertEquals("Okay, I didn't send it.", r.vm.state.value.notice)
    }

    @Test fun `send button and spoken yes share one confirmation`() = runTest(dispatcher) {
        val r = rig()
        collect(r); advanceUntilIdle()
        r.dialog.proposeReply(message, "Running late"); r.vm.confirm(); advanceUntilIdle()
        assertEquals(1, r.messaging.sent.size)
        assertFalse(r.vm.state.value.notice.orEmpty().contains("delivered"))
    }

    @Test fun `coming back to the screen re-checks access and media`() = runTest(dispatcher) {
        val r = rig()
        r.vm.onResume()
        assertEquals(1, r.access.refreshes)
        assertEquals(1, r.media.refreshes)
    }

    @Test fun `without a vehicle the car controls explain that a vehicle comes first`() = runTest(dispatcher) {
        val r = Rig(this, true, emptyList(), hasVehicle = false)
        backgroundScope.launch { r.vm.state.collect {} }
        advanceUntilIdle()
        assertTrue(r.vm.state.value.vehicleControls.all { it.reason == UnavailableReason.NO_VEHICLE })
    }
}
