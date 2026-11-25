package com.aicarcompanion.app.handsfree

import com.aicarcompanion.app.assistant.conversation.AssistantReply
import com.aicarcompanion.app.assistant.conversation.AssistantRequest
import com.aicarcompanion.app.assistant.conversation.ReasoningProvider
import com.aicarcompanion.app.assistant.conversation.ReplyBasis
import com.aicarcompanion.app.connectivity.ConnectionLog
import com.aicarcompanion.app.handsfree.android.AndroidHandsFreeCapabilities
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Drive routing: voice commands reach the right service, say only what's true, and never leak text. */
class HandsFreeDriveRoutingTest {
    private class RecordingDelegate : ReasoningProvider {
        val seen = mutableListOf<String>()
        override suspend fun respond(request: AssistantRequest): AssistantReply {
            seen += request.utterance
            return AssistantReply("vehicle answer", ReplyBasis.GENERAL_KNOWLEDGE)
        }
    }

    private val delegate = RecordingDelegate()
    private val media = FakeMedia()
    private val message = IncomingMessage("k1", "Sarah", "Dinner at seven? Bring the red folder", "Messages", canReply = true)
    private val messaging = FakeMessaging(listOf(message))
    private val calls = FakeCalls()
    private val dialog = HandsFreeDialog(messaging, calls)
    private val routed = mutableListOf<Pair<String, String>>()

    private fun provider(access: Boolean = true) = HandsFreeReasoningProvider(
        delegate, AndroidHandsFreeCapabilities(notificationAccess = { access }), media, messaging, dialog,
    ) { k, s -> routed += k to s }

    private suspend fun say(text: String, access: Boolean = true) = provider(access).respond(AssistantRequest(text, "Penelope"))

    @Test fun `replies never hardcode Penelope when the user renamed the assistant`() = runTest {
        suspend fun jarvis(text: String, access: Boolean) = provider(access).respond(AssistantRequest(text, "Jarvis"))
        val replies = listOf(
            jarvis("read my messages", false).text,
            jarvis("call Sam", false).text,
            jarvis("unlock the doors", true).text,
        )
        replies.forEach { assertFalse(it, it.contains("Penelope")) }
        PlatformRequirement.entries.forEach { assertFalse(it.description.contains("Penelope")) }
        HandsFreeCapability.entries.forEach { c ->
            listOf(CapabilityStatus.NotImplemented, CapabilityStatus.NotSupported).forEach {
                assertFalse(HandsFreeSafety.unavailableReply(c, it).contains("Penelope"))
            }
        }
    }

    @Test fun `media commands ask the media app and say so honestly`() = runTest {
        assertEquals("I asked your media app to pause.", say("pause the music").text)
        assertEquals("I asked your media app to play.", say("play some music").text)
        assertEquals("I asked your media app to skip to the next track.", say("next track").text)
        assertEquals(listOf(MediaAction.PAUSE, MediaAction.PLAY, MediaAction.NEXT), media.performed)
        assertTrue(delegate.seen.isEmpty())
    }

    @Test fun `volume commands change the volume`() = runTest {
        assertEquals("Okay, volume up.", say("turn the volume up").text)
        assertEquals(listOf(VolumeDirection.UP), media.volumeChanges)
        media.volume.value = null
        assertEquals("Volume is set by your car.", say("volume down").text)
    }

    @Test fun `reading messages without notification access explains the setup`() = runTest {
        val reply = say("read my messages", access = false)
        assertTrue(reply.text.contains("notification access"))
        assertFalse(reply.spokenText.contains("Dinner"))
        assertEquals("NeedsSetup", routed.single().second)
    }

    @Test fun `the message body is spoken but never in the transcript text`() = runTest {
        val reply = say("read my messages")
        assertEquals("I read 1 message from Sarah aloud.", reply.text)
        assertTrue(reply.spokenText.contains("Dinner at seven? Bring the red folder"))
        assertTrue(reply.spokenText.contains("Say reply to answer"))
        assertFalse(reply.text.contains("Dinner"))
        assertTrue(delegate.seen.isEmpty())
    }

    @Test fun `long messages are shortened when read aloud`() = runTest {
        messaging.messages[0] = message.copy(text = "x".repeat(900))
        assertTrue(say("read my messages").spokenText.length < 400)
    }

    @Test fun `diagnostics get only the command kind and status`() = runTest {
        val log = ConnectionLog(now = { Instant.EPOCH })
        val p = HandsFreeReasoningProvider(delegate, AndroidHandsFreeCapabilities(notificationAccess = { true }), media, messaging, dialog) { kind, status ->
            log.record(com.aicarcompanion.app.connectivity.LogArea.APP, "Hands-free command", mapOf("command" to kind, "status" to status))
        }
        p.respond(AssistantRequest("text Sarah saying meet me at the red folder place", "Penelope"))
        p.respond(AssistantRequest("call 5551234567", "Penelope"))
        val exported = log.export()
        listOf("Sarah", "red folder", "5551234567", "meet me").forEach { assertFalse(it, exported.contains(it)) }
        assertTrue(exported.contains("send_message"))
    }

    @Test fun `a reply with a dictated body asks for confirmation and sends nothing yet`() = runTest {
        val p = provider()
        val ask = p.respond(AssistantRequest("reply saying I'll be there at seven", "Penelope"))
        assertFalse(ask.text.contains("seven"))
        assertTrue(ask.spokenText.contains("I'll be there at seven"))
        assertTrue(messaging.sent.isEmpty())
        // The yes goes to the dialog, not to the router or the AI.
        val done = p.respond(AssistantRequest("yes", "Penelope"))
        assertEquals(1, messaging.sent.size)
        assertTrue(done.text.startsWith("I passed your reply to Messages to send."))
        assertFalse(done.text.contains("delivered"))
        assertTrue(delegate.seen.isEmpty())
    }

    @Test fun `a dictated reply is never routed or sent to the AI`() = runTest {
        val p = provider()
        p.respond(AssistantRequest("reply", "Penelope"))
        val readBack = p.respond(AssistantRequest("call my doctor about the results", "Penelope"))
        assertTrue(readBack.spokenText.contains("call my doctor"))
        assertTrue(delegate.seen.isEmpty())
        assertTrue(calls.dialed.isEmpty())
        assertEquals(listOf("send_message"), routed.map { it.first })
    }

    @Test fun `replying with nothing to reply to says so`() = runTest {
        messaging.messages.clear()
        assertEquals(HandsFreeSafety.NO_REPLYABLE_TEXT, say("reply saying ok").text)
        messaging.messages += message.copy(canReply = false)
        assertEquals(HandsFreeSafety.NO_REPLYABLE_TEXT, say("reply saying ok").text)
    }

    @Test fun `calling a spoken number asks for confirmation before opening the dialer`() = runTest {
        val p = provider()
        val ask = p.respond(AssistantRequest("call 5551234567", "Penelope"))
        assertTrue(dialog.pending.value is PendingAction.ConfirmDial)
        assertTrue(ask.spokenText.contains("5 5 5 1 2 3 4 5 6 7"))
        assertFalse(ask.text.contains("555"))
        assertTrue(calls.dialed.isEmpty())
        p.respond(AssistantRequest("yes", "Penelope"))
        assertEquals(listOf(FakeCalls.Dialed("5551234567", true)), calls.dialed)
    }

    @Test fun `calling a name is answered honestly and nothing is dialed`() = runTest {
        val reply = say("call Mom")
        assertEquals(HandsFreeSafety.NAME_CALL_TEXT, reply.text)
        assertTrue(reply.text.contains("Call someone"))
        assertFalse(reply.text.contains("Mom"))
        assertTrue(dialog.pending.value == null && calls.dialed.isEmpty() && delegate.seen.isEmpty())
    }

    @Test fun `car control requests are unavailable and never reach the AI`() = runTest {
        val reply = say("turn on the heated seats")
        assertTrue(reply.text.contains("can't control your car's features"))
        assertTrue(reply.text.contains("OBD-II adapters read data"))
        assertTrue(delegate.seen.isEmpty())
        assertEquals("vehicle_control" to "NotSupported", routed.single())
    }

    @Test fun `answering calls stays an honest not-yet`() = runTest {
        assertTrue(say("answer the call").text.startsWith("I can't answer phone calls yet."))
    }

    @Test fun `ordinary questions still go to the assistant`() = runTest {
        assertEquals("vehicle answer", say("what does P0171 mean").text)
        assertEquals(listOf("what does P0171 mean"), delegate.seen)
    }

    @Test fun `resetting the conversation clears a pending confirmation`() = runTest {
        val p = provider()
        p.respond(AssistantRequest("call 5551234567", "Penelope"))
        p.resetContext()
        assertEquals(null, dialog.pending.value)
    }
}

class HandsFreeListenerConnectionTest {
    @Test fun `access on but listener not connected says it can't see notifications and never no new messages`() = kotlinx.coroutines.test.runTest {
        val caps = AndroidHandsFreeCapabilities({ true }, { false })
        val status = caps.status(HandsFreeCapability.READ_MESSAGES)
        assertEquals(CapabilityStatus.NeedsSetup(listOf(PlatformRequirement.NOTIFICATION_LISTENER_CONNECTION)), status)
        val provider = HandsFreeReasoningProvider(
            object : ReasoningProvider {
                override suspend fun respond(request: AssistantRequest) = AssistantReply("vehicle answer", ReplyBasis.GENERAL_KNOWLEDGE)
            },
            caps, FakeMedia(), FakeMessaging(), HandsFreeDialog(FakeMessaging(), FakeCalls()),
        )
        val reply = provider.respond(AssistantRequest("read my messages", "Penelope"))
        assertEquals(HandsFreeSafety.NOT_CONNECTED_TEXT, reply.text)
        assertFalse(reply.text.contains("no new messages", ignoreCase = true))
    }

    @Test fun `group chat readout names the group and replies are read back with it`() = kotlinx.coroutines.test.runTest {
        val group = IncomingMessage("k", "Sarah", "Dinner?", "Chat", canReply = true, conversationId = "c|Family", groupTitle = "Family")
        val messaging = FakeMessaging(listOf(group))
        val dialog = HandsFreeDialog(messaging, FakeCalls())
        val provider = HandsFreeReasoningProvider(
            object : ReasoningProvider {
                override suspend fun respond(request: AssistantRequest) = AssistantReply("x", ReplyBasis.GENERAL_KNOWLEDGE)
            },
            AndroidHandsFreeCapabilities({ true }), FakeMedia(), messaging, dialog,
        )
        val read = provider.respond(AssistantRequest("read my messages", "Penelope"))
        assertEquals("I read 1 message from Sarah in Family aloud.", read.text)
        assertTrue(read.spokenText.startsWith("Sarah in Family on Chat: Dinner?"))
        val back = provider.respond(AssistantRequest("reply saying on my way", "Penelope"))
        assertTrue(back.spokenText.startsWith("Reply in Family: on my way."))
    }
}
