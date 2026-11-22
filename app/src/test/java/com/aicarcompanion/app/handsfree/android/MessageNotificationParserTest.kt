package com.aicarcompanion.app.handsfree.android

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.aicarcompanion.app.handsfree.ActionResult
import com.aicarcompanion.app.handsfree.HandsFreeSafety
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MessageNotificationParserTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val own = context.packageName

    private fun pending() = PendingIntent.getBroadcast(context, 0, Intent("test.REPLY").setPackage(context.packageName), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun builder() = NotificationCompat.Builder(context, "c").setSmallIcon(android.R.drawable.ic_dialog_info)

    private fun conversation(text: String = "Dinner at seven?", sender: String = "Sarah", reply: Boolean = true, pi: PendingIntent = pending()): Notification {
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Me").build())
            .addMessage(text, System.currentTimeMillis(), Person.Builder().setName(sender).build())
        val b = builder().setStyle(style)
        if (reply) {
            b.addAction(
                NotificationCompat.Action.Builder(0, "Reply", pi)
                    .addRemoteInput(RemoteInput.Builder("reply_key").build())
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).build(),
            )
        }
        return b.build()
    }

    private fun parse(n: Notification, pkg: String = "other.chat", key: String = "k1") =
        MessageNotificationParser.parse(key, pkg, "Chat App", n, own)

    @Test fun `a conversation notification becomes a message that can be replied to`() {
        val m = parse(conversation())!!.message
        assertEquals("k1", m.id)
        assertEquals("Sarah", m.senderDisplayName)
        assertEquals("Dinner at seven?", m.text)
        assertEquals("Chat App", m.appName)
        assertTrue(m.canReply)
    }

    @Test fun `a message without a reply action can be read but not answered`() {
        assertFalse(parse(conversation(reply = false))!!.message.canReply)
    }

    @Test fun `category message notifications use the title and text`() {
        val n = builder().setCategory(Notification.CATEGORY_MESSAGE).setContentTitle("Sam").setContentText("On my way").build()
        val m = parse(n)!!.message
        assertEquals("Sam", m.senderDisplayName)
        assertEquals("On my way", m.text)
    }

    @Test fun `non-message, own, summary and ongoing notifications are ignored`() {
        val plain = builder().setContentTitle("Download").setContentText("50%").build()
        assertNull(parse(plain))
        assertNull(MessageNotificationParser.parse("k", own, null, conversation(), own))
        val summary = builder().setCategory(Notification.CATEGORY_MESSAGE).setContentTitle("Sam").setContentText("x").setGroup("g").setGroupSummary(true).build()
        assertNull(parse(summary))
        val ongoing = builder().setCategory(Notification.CATEGORY_MESSAGE).setContentTitle("Sam").setContentText("x").setOngoing(true).build()
        assertNull(parse(ongoing))
        val empty = builder().setCategory(Notification.CATEGORY_MESSAGE).setContentTitle("Sam").build()
        assertNull(parse(empty))
    }

    @Test fun `the reply intent carries the text as the user's own free-form input`() {
        val handle = parse(conversation())!!.reply!!
        val intent = MessageNotificationParser.fillReply(handle, "Running late")
        val results = RemoteInput.getResultsFromIntent(intent)!!
        assertEquals("Running late", results.getCharSequence("reply_key").toString())
        assertEquals(RemoteInput.SOURCE_FREE_FORM_INPUT, RemoteInput.getResultsSource(intent))
    }

    @Test fun `parsed messages never expose names or text in toString`() {
        val parsed = parse(conversation("Secret plan at the bridge", "Sarah"))!!
        val text = listOf(parsed.toString(), parsed.message.toString(), parsed.reply.toString()).joinToString()
        assertFalse(text.contains("Sarah") || text.contains("Secret"))
    }

    // ---- The in-memory message store ----------------------------------------------------------------

    @Test fun `the service keeps at most 20 messages and drops removed ones`() = runTest {
        val service = AndroidMessagingService(context)
        (1..25).forEach { service.onPosted(parse(conversation("m$it"), key = "k$it")!!) }
        assertEquals(20, service.unreadCount.value)
        assertEquals("k6", service.unread().first().id)
        assertEquals("k25", service.unread().last().id)
        service.onRemoved("k25")
        assertEquals(19, service.unread().size)
        service.clear()
        assertEquals(0, service.unreadCount.value)
        assertTrue(service.unread().isEmpty())
    }

    @Test fun `a repeated notification replaces the older one`() = runTest {
        val service = AndroidMessagingService(context)
        service.onPosted(parse(conversation("first"))!!)
        service.onPosted(parse(conversation("second"))!!)
        assertEquals(listOf("second"), service.unread().map { it.text })
    }

    @Test fun `reply refuses unless confirmed and sends through the notification's own action`() = runTest {
        val service = AndroidMessagingService(context)
        val parsed = parse(conversation())!!
        service.onPosted(parsed)
        assertEquals(ActionResult.NeedsConfirmation, service.reply(parsed.message, "Hi", confirmed = false))
        assertNull(shadowOf(context as Application).broadcastIntents.firstOrNull())

        assertEquals(ActionResult.Requested, service.reply(parsed.message, "Hi", confirmed = true))
        val sent = shadowOf(context as Application).broadcastIntents.single()
        assertEquals("test.REPLY", sent.action)
        assertEquals("Hi", RemoteInput.getResultsFromIntent(sent)!!.getCharSequence("reply_key").toString())
    }

    @Test fun `replying to a message that is gone or canceled fails honestly`() = runTest {
        val service = AndroidMessagingService(context)
        val gone = parse(conversation(), key = "gone")!!
        assertEquals(ActionResult.Failed(HandsFreeSafety.REPLY_GONE_TEXT), service.reply(gone.message, "Hi", confirmed = true))

        val pi = pending()
        val parsed = parse(conversation(pi = pi), key = "cancelled")!!
        service.onPosted(parsed)
        pi.cancel()
        val result = service.reply(parsed.message, "Hi", confirmed = true)
        assertEquals(ActionResult.Failed(HandsFreeSafety.REPLY_GONE_TEXT), result)
    }

    @Test fun `a group conversation carries its group title and a conversation id`() {
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Me").build())
            .setConversationTitle("Family").setGroupConversation(true)
            .addMessage("Dinner?", System.currentTimeMillis(), Person.Builder().setName("Sarah").build())
        val n = builder().setStyle(style).build()
        val m = parse(n)!!.message
        assertEquals("Sarah", m.senderDisplayName)
        assertEquals("Family", m.groupTitle)
        assertEquals("other.chat|Family", m.conversationId)
        assertNull(parse(conversation())!!.message.groupTitle)
        assertFalse(m.toString().contains("Family"))
    }

    @Test fun `a reply cannot go to a different conversation that reuses the notification key`() = runTest {
        val service = AndroidMessagingService(context)
        val a = parse(conversation("hi", "Sarah"), key = "k")!!
        service.onPosted(a)
        val b = parse(conversation("send money", "Mallory"), key = "k")!!
        service.onPosted(b)
        // The caller still holds A's message: the service must refuse.
        assertEquals(ActionResult.Failed(HandsFreeSafety.REPLY_GONE_TEXT), service.reply(a.message, "Running late", confirmed = true))
        assertTrue(shadowOf(context as Application).broadcastIntents.isEmpty())
    }

    @Test fun `an updated notification that is no longer a message drops the old message`() = runTest {
        val messaging = AndroidMessagingService(context)
        val hub = NotificationHub(context, AndroidNotificationAccess(context), messaging, com.aicarcompanion.app.handsfree.FakeMedia())
        hub.onPosted("k", "other.chat", conversation("hello"))
        assertEquals(1, messaging.unreadCount.value)
        hub.onPosted("k", "other.chat", builder().setContentTitle("Download").setContentText("done").build())
        assertEquals(0, messaging.unreadCount.value)
        assertTrue(messaging.unread().isEmpty())
    }

    @Test fun `connecting clears stale messages, sets connected, and disconnecting clears again`() = runTest {
        val messaging = AndroidMessagingService(context)
        val access = AndroidNotificationAccess(context)
        val hub = NotificationHub(context, access, messaging, com.aicarcompanion.app.handsfree.FakeMedia())
        messaging.onPosted(parse(conversation("stale"), key = "old")!!)
        hub.onConnected(emptyList())
        assertTrue(access.connected.value)
        assertEquals(0, messaging.unreadCount.value)
        messaging.onPosted(parse(conversation("x"), key = "n")!!)
        hub.onDisconnected()
        assertFalse(access.connected.value)
        assertEquals(0, messaging.unreadCount.value)
    }

    @Test fun `assistant replies never print their text`() {
        val reply = com.aicarcompanion.app.assistant.conversation.AssistantReply(
            "private body", com.aicarcompanion.app.assistant.conversation.ReplyBasis.CONVERSATION, spokenText = "private spoken",
        )
        assertFalse(reply.toString().contains("private"))
    }
}
