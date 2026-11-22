package com.aicarcompanion.app.handsfree

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsFreeDialogTest {
    private val message = IncomingMessage("k1", "Sarah", "Where are you?", "Messages", canReply = true)
    private val messaging = FakeMessaging(listOf(message))
    private val calls = FakeCalls()
    private val clock = FakeClock()
    private val dialog = HandsFreeDialog(messaging, calls, clock)

    @Test fun `yes sends the reply exactly once with confirmation`() = runTest {
        val prompt = dialog.proposeReply(message, "Running late")
        assertTrue(prompt.spokenText.contains("Running late"))
        assertFalse("the transcript text has no body", prompt.text.contains("Running late"))
        assertTrue(dialog.pending.value is PendingAction.ConfirmReply)
        val reply = dialog.onUtterance("yes")!!
        assertEquals(listOf(FakeMessaging.Sent("k1", "Running late", true)), messaging.sent)
        assertTrue(reply.text.contains("Messages"))
        assertFalse(reply.text.contains("delivered"))
        assertNull(dialog.pending.value)
        // A second yes is a no-op: nothing is pending, so nothing is sent.
        assertNull(dialog.onUtterance("yes"))
        assertEquals(1, messaging.sent.size)
    }

    @Test fun `confirming twice sends once`() = runTest {
        dialog.proposeReply(message, "Running late")
        dialog.confirm(); dialog.confirm()
        assertEquals(1, messaging.sent.size)
    }

    @Test fun `anything other than a clear yes cancels`() = runTest {
        listOf("no", "maybe", "yes please do", "send", "what", "call").forEach { said ->
            dialog.proposeReply(message, "Running late")
            val reply = dialog.onUtterance(said)!!
            assertEquals(said, HandsFreeSafety.REPLY_CANCELLED_TEXT, reply.text)
            assertNull(dialog.pending.value)
        }
        assertTrue(messaging.sent.isEmpty())
    }

    @Test fun `every accepted yes word confirms a reply`() = runTest {
        listOf("yes", "Yeah", "yep", "send it", "Yes, send it", "confirm.").forEach { said ->
            dialog.proposeReply(message, "Hi")
            dialog.onUtterance(said)
        }
        assertEquals(6, messaging.sent.size)
    }

    @Test fun `dial accepts call and dial but a reply does not`() = runTest {
        dialog.proposeDial(CallTarget(null, "5550100"))
        dialog.onUtterance("dial")
        assertEquals(listOf(FakeCalls.Dialed("5550100", true)), calls.dialed)

        dialog.proposeReply(message, "Hi")
        dialog.onUtterance("call")
        assertTrue(messaging.sent.isEmpty())
    }

    @Test fun `the dictated text is kept in the dialog and never routed on`() = runTest {
        val ask = dialog.proposeReply(message, null)
        assertTrue(dialog.pending.value is PendingAction.Reply)
        assertEquals(HandsFreeSafety.ASK_REPLY_TEXT, ask.text)
        val readBack = dialog.onUtterance("call my lawyer")!! // would be a place_call command if routed
        assertTrue(readBack.spokenText.contains("call my lawyer"))
        assertEquals(PendingAction.ConfirmReply("k1", "Sarah", "call my lawyer", "Messages"), dialog.pending.value)
        assertTrue(calls.dialed.isEmpty())
    }

    @Test fun `the assistant's own prompt can't confirm`() = runTest {
        val prompt = dialog.proposeReply(message, "Running late")
        val echo = dialog.onUtterance(prompt.spokenText)!!
        assertEquals(HandsFreeSafety.REPLY_CANCELLED_TEXT, echo.text)
        assertTrue(messaging.sent.isEmpty())
        val dial = dialog.proposeDial(CallTarget(null, "5550100"))
        dialog.onUtterance(dial.spokenText)
        assertTrue(calls.dialed.isEmpty())
    }

    @Test fun `a pending action times out after 30 seconds`() = runTest {
        dialog.proposeReply(message, "Running late")
        clock.now = 30_001
        assertNull("the yes arrives too late and is treated as ordinary speech", dialog.onUtterance("yes"))
        assertTrue(messaging.sent.isEmpty())
        assertNull(dialog.pending.value)

        dialog.proposeDial(CallTarget(null, "5550100"))
        clock.now += 31_000
        dialog.expireIfStale()
        assertNull(dialog.pending.value)
        assertEquals(HandsFreeSafety.NOTHING_PENDING_TEXT, dialog.confirm().text)
        assertTrue(calls.dialed.isEmpty())
    }

    @Test fun `a message that left the notifications can't be replied to`() = runTest {
        dialog.proposeReply(message, "Hi")
        messaging.messages.clear()
        assertEquals(HandsFreeSafety.REPLY_GONE_TEXT, dialog.confirm().text)
        assertTrue(messaging.sent.isEmpty())
    }

    @Test fun `reset clears what is waiting`() = runTest {
        dialog.proposeReply(message, "Hi")
        dialog.reset()
        assertNull(dialog.pending.value)
        assertEquals(HandsFreeSafety.NOTHING_PENDING_TEXT, dialog.cancel().text)
    }

    @Test fun `pending actions and prompts never expose names, numbers or text in toString`() {
        val items = listOf(
            PendingAction.Reply("k", "Sarah", "Messages"),
            PendingAction.ConfirmReply("k", "Sarah", "secret body", "Messages"),
            PendingAction.ConfirmDial(CallTarget("Sam", "5550100")),
            message, CallTarget("Sam", "5550100"),
        ).joinToString { it.toString() }
        listOf("Sarah", "secret", "Sam", "5550100", "Where are you").forEach { assertFalse(it, items.contains(it)) }
        assertNotNull(dialog)
    }

    @Test fun `prompts never consist of an accepted word alone`() {
        val prompts = listOf(
            HandsFreeSafety.DIAL_PROMPT_TEXT, HandsFreeSafety.ASK_REPLY_TEXT, HandsFreeSafety.CONFIRM_REPLY_TEXT,
            HandsFreeSafety.confirmReplyPrompt("Sarah", "yes"), HandsFreeSafety.dialPrompt(CallTarget(null, "123")),
        )
        prompts.forEach {
            assertFalse(it, HandsFreeSafety.isAffirmative(it, forDial = true))
        }
    }
}
