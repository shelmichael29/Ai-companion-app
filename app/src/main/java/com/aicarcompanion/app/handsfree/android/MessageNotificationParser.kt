package com.aicarcompanion.app.handsfree.android

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.aicarcompanion.app.handsfree.IncomingMessage

/** How to answer a message notification: its own reply action. Never stored on disk. */
class ReplyHandle(val pendingIntent: PendingIntent, val remoteInputs: Array<RemoteInput>) {
    override fun toString() = "ReplyHandle(redacted)"
}

class ParsedMessage(val message: IncomingMessage, val reply: ReplyHandle?) {
    override fun toString() = "ParsedMessage(redacted)"
}

/**
 * Turns a notification into a message, but only real messages: conversation (MessagingStyle) or
 * message-category notifications from other apps. Group summaries, ongoing notifications and
 * Penelope's own are ignored. Pure logic over [Notification], so it's unit-tested without a phone.
 */
object MessageNotificationParser {

    fun parse(key: String, packageName: String, appName: String?, notification: Notification, ownPackage: String): ParsedMessage? {
        if (packageName == ownPackage) return null
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return null
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val isMessage = style != null || notification.category == Notification.CATEGORY_MESSAGE
        if (!isMessage) return null

        val (sender, text) = extract(style, notification) ?: return null
        val group = style?.takeIf { it.isGroupConversation }?.conversationTitle?.toString()?.trim()?.ifEmpty { null }
        val reply = replyAction(notification)
        val conversationId = "$packageName|${group ?: sender}"
        return ParsedMessage(
            IncomingMessage(key, sender, text, appName, canReply = reply != null, conversationId = conversationId, groupTitle = group),
            reply,
        )
    }

    private fun extract(style: NotificationCompat.MessagingStyle?, n: Notification): Pair<String, String>? {
        val last = style?.messages?.lastOrNull()
        if (last != null) {
            val text = last.text?.toString()?.trim().orEmpty()
            val sender = last.person?.name?.toString()?.trim()?.ifEmpty { null }
                ?: style.conversationTitle?.toString()?.trim()?.ifEmpty { null }
                ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()?.ifEmpty { null }
            if (text.isNotEmpty() && sender != null) return sender to text
        }
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        return if (title.isNotEmpty() && body.isNotEmpty()) title to body else null
    }

    private fun replyAction(n: Notification): ReplyHandle? {
        val actions = (0 until NotificationCompat.getActionCount(n)).mapNotNull { NotificationCompat.getAction(n, it) }
        fun usable(a: NotificationCompat.Action) =
            a.actionIntent != null && a.remoteInputs?.any { it.allowFreeFormInput } == true
        val chosen = actions.firstOrNull { it.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_REPLY && usable(it) }
            ?: actions.firstOrNull { usable(it) }
        return chosen?.let { ReplyHandle(it.actionIntent!!, it.remoteInputs!!) }
    }

    /** The intent that fills the reply box with [text] and marks it as typed by the user. */
    fun fillReply(handle: ReplyHandle, text: String): Intent {
        val intent = Intent()
        val results = Bundle()
        handle.remoteInputs.forEach { results.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(handle.remoteInputs, intent, results)
        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        return intent
    }
}
