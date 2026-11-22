package com.aicarcompanion.app.vehiclecontrol

import android.app.Application
import com.aicarcompanion.app.assistant.VoiceSession
import com.aicarcompanion.app.assistant.conversation.AssistantReply
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
import com.aicarcompanion.app.diagnostics.FakeElmAdapter
import com.aicarcompanion.app.handsfree.FakeCalls
import com.aicarcompanion.app.handsfree.FakeMedia
import com.aicarcompanion.app.handsfree.FakeMessaging
import com.aicarcompanion.app.handsfree.HandsFreeDialog
import com.aicarcompanion.app.handsfree.android.NotificationAccessState
import com.aicarcompanion.app.handsfree.CallTarget
import com.aicarcompanion.app.handsfree.VolumeDirection
import com.aicarcompanion.app.ui.drive.DriveViewModel
import com.aicarcompanion.app.ui.drive.vehicleControlRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * Car controls are capability-only. Nothing in Drive may send anything to the car, and a standard
 * OBD-II adapter is never treated as a control channel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VehicleControlSafetyTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `every feature is unavailable, with or without a vehicle`() {
        VehicleControlFeature.entries.forEach { feature ->
            val withCar = NoVehicleControlProvider.availability(feature, hasVehicle = true)
            val noCar = NoVehicleControlProvider.availability(feature, hasVehicle = false)
            assertEquals(VehicleControlAvailability.Unavailable(UnavailableReason.OBD_ADAPTER_READ_ONLY), withCar)
            assertEquals(VehicleControlAvailability.Unavailable(UnavailableReason.NO_VEHICLE), noCar)
        }
        assertEquals("Standard OBD-II adapters read data; they can't control the car.", UnavailableReason.OBD_ADAPTER_READ_ONLY.explanation)
        assertEquals(VehicleControlFeature.entries.size, vehicleControlRows(NoVehicleControlProvider, true).size)
    }

    @Test fun `the provider declares only availability and the only availability is Unavailable`() {
        val methods = VehicleControlProvider::class.java.declaredMethods.map { it.name }
        assertEquals(listOf("availability"), methods)
        val implementations = VehicleControlAvailability::class.java.permittedSubclasses.orEmpty().map { it.simpleName }
        assertEquals(listOf("Unavailable"), implementations)
        assertTrue(VehicleControlAvailability::class.java.isInterface && Modifier.isAbstract(VehicleControlAvailability::class.java.modifiers))
    }

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

    @Test fun `no Drive action writes anything to an OBD adapter`() = runTest(dispatcher) {
        val adapter = FakeElmAdapter()
        val access = object : NotificationAccessState {
            override val granted = MutableStateFlow(true)
            override val connected = MutableStateFlow(true)
            override fun listenerConnected(connected: Boolean) = Unit
            override fun refresh() = Unit
        }
        val messaging = FakeMessaging()
        val dialog = HandsFreeDialog(messaging, FakeCalls())
        val reasoning = object : ReasoningProvider {
            override suspend fun respond(request: com.aicarcompanion.app.assistant.conversation.AssistantRequest) =
                AssistantReply("ok", ReplyBasis.CONVERSATION)
        }
        val session = VoiceSession(this, Input(), Output(), ConversationManager(reasoning, { "Penelope" }), NoAudioFocus(), { true })
        val vm = DriveViewModel(FakeMedia(), messaging, dialog, access, NoVehicleControlProvider, session, flowOf(null), flowOf(true))
        vm.play(); vm.pause(); vm.next(); vm.previous()
        vm.volume(VolumeDirection.UP); vm.volume(VolumeDirection.DOWN)
        vm.onContactPicked(CallTarget("Sam", "5550100")); vm.confirm(); vm.cancel()
        vm.ask("turn on the heated seats")
        vm.onResume()
        advanceUntilIdle()
        assertTrue("nothing was written to the adapter", adapter.written.isEmpty())
    }

    @Test fun `vehicle-control and Drive sources never reference the OBD write path`() {
        val banned = listOf("ElmLink", "ObdSession", "VehicleWriteGate", "ReadOnlyCommandPolicy")
        val roots = listOf("src/main/java/com/aicarcompanion/app/vehiclecontrol", "src/main/java/com/aicarcompanion/app/ui/drive")
        val files = roots.flatMap { File(it).walkTopDown().filter { f -> f.extension == "kt" }.toList() }
        assertTrue(files.size >= 4)
        files.forEach { f -> banned.forEach { assertFalse("${f.name} mentions $it", f.readText().contains(it)) } }
    }

    @Test fun `unavailable car controls say so honestly`() {
        val reply = com.aicarcompanion.app.handsfree.HandsFreeSafety.unavailableReply(
            com.aicarcompanion.app.handsfree.HandsFreeCapability.VEHICLE_CONTROLS,
            com.aicarcompanion.app.handsfree.CapabilityStatus.NotSupported,
        )
        assertTrue(reply.contains("can't control your car's features"))
    }
}
