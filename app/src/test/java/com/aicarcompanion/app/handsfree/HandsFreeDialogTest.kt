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
        dialog.onUtterance("call")
        assertTrue("call alone is not a yes for the dialer", calls.dialed.isEmpty())
        dialog.proposeDial(CallTarget(null, "5550100"))
        dialog.onUtterance("dial")
        assertEquals(listOf(FakeCalls.Dialed("5550100", true)), calls.dialed)

        dialog.proposeReply(message, "Hi")
        dialog.onUtterance("dial")
        assertTrue(messaging.sent.isEmpty())
    }

    @Test fun `the dictated text is kept in the dialog and never routed on`() = runTest {
        val ask = dialog.proposeReply(message, null)
        assertTrue(dialog.pending.value is PendingAction.Reply)
        assertEquals(HandsFreeSafety.ASK_REPLY_TEXT, ask.text)
        val readBack = dialog.onUtterance("call my lawyer")!! // would be a place_call command if routed
        assertTrue(readBack.spokenText.contains("call my lawyer"))
        assertEquals(PendingAction.ConfirmReply("k1", "Sarah", "call my lawyer", "Messages", "k1", null), dialog.pending.value)
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
        val late = dialog.onUtterance("yes")!!
        assertEquals("a late yes is answered, not passed on as ordinary speech", HandsFreeSafety.REPLY_TIMED_OUT_TEXT, late.text)
        assertTrue(messaging.sent.isEmpty())
        assertNull(dialog.pending.value)
        assertNull("the marker is used up by one utterance", dialog.onUtterance("yes"))

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

    @Test fun `no short ending of any spoken prompt is an accepted yes`() {
        val prompts = listOf(
            HandsFreeSafety.confirmReplyPrompt("Sarah", "ok see you there"),
            HandsFreeSafety.confirmReplyPrompt("Sarah", "ok", "Family"),
            HandsFreeSafety.dialPrompt(CallTarget(null, "5550100")),
            HandsFreeSafety.dialPrompt(CallTarget("Sam", "5550100")),
            HandsFreeSafety.askReplyPrompt("Sarah"), HandsFreeSafety.CONFIRM_REPLY_TEXT, HandsFreeSafety.DIAL_PROMPT_TEXT,
        )
        prompts.forEach { prompt ->
            val words = prompt.trim().split(Regex("\\s+"))
            for (n in 1..3) {
                val ending = words.takeLast(n).joinToString(" ")
                assertFalse("ending '$ending' of '$prompt'", HandsFreeSafety.isAffirmative(ending, forDial = true))
                assertFalse("ending '$ending' of '$prompt'", HandsFreeSafety.isAffirmative(ending, forDial = false))
            }
        }
    }

    @Test fun `a late yes after an expired reply or dictation never goes on as ordinary speech`() = runTest {
        dialog.proposeReply(message, null)
        clock.now = 31_000
        assertEquals(HandsFreeSafety.REPLY_TIMED_OUT_TEXT, dialog.onUtterance("tell him I'm on my way")!!.text)
        assertTrue(messaging.sent.isEmpty())
        // After the window the marker is gone.
        dialog.proposeDial(CallTarget(null, "5550100"))
        clock.now += 31_000
        dialog.expireIfStale()
        clock.now += 61_000
        assertNull(dialog.onUtterance("yes"))
        assertTrue(calls.dialed.isEmpty())
    }

    @Test fun `a notification key reused by another conversation can't receive the reply`() = runTest {
        dialog.proposeReply(message, "Running late")
        // Same key, different person and conversation.
        messaging.messages[0] = IncomingMessage("k1", "Mallory", "Send me money", "Messages", canReply = true, conversationId = "chat|Mallory")
        assertEquals(HandsFreeSafety.REPLY_GONE_TEXT, dialog.confirm().text)
        assertTrue(messaging.sent.isEmpty())
    }

    @Test fun `group chats are named in the read-back and the card state`() = runTest {
        val group = message.copy(groupTitle = "Family", conversationId = "chat|Family")
        val prompt = dialog.proposeReply(group, "On my way")
        assertTrue(prompt.spokenText.startsWith("Reply in Family: On my way."))
        assertEquals("Family", (dialog.pending.value as PendingAction.ConfirmReply).groupTitle)
        dialog.cancel()
        assertTrue(dialog.proposeReply(group, null).spokenText.contains("in Family"))
        assertEquals("Sarah in Family", HandsFreeSafety.senderLabel("Sarah", "Family"))
    }

    @Test fun `hearing its own question is not taken as the reply`() = runTest {
        val ask = dialog.proposeReply(message, null)
        val again = dialog.onUtterance(ask.spokenText)!!
        assertEquals(HandsFreeSafety.NEED_REPLY_WORDS_TEXT, again.text)
        assertTrue("still waiting for the reply", dialog.pending.value is PendingAction.Reply)
        dialog.onUtterance("what would you like to say")
        assertTrue(dialog.pending.value is PendingAction.Reply)
        dialog.onUtterance("be there soon")
        assertTrue(dialog.pending.value is PendingAction.ConfirmReply)
    }

    @Test fun `the sent message says the reply was passed on, never delivered`() {
        assertEquals("I passed your reply to Messages to send.", HandsFreeSafety.replySentText("Messages"))
        assertFalse(HandsFreeSafety.DIAL_OPENED_TEXT.startsWith("I opened"))
    }
}
