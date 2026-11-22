package com.aicarcompanion.app.handsfree.android

import android.app.PendingIntent
import android.content.Context
import com.aicarcompanion.app.handsfree.ActionResult
import com.aicarcompanion.app.handsfree.HandsFreeSafety
import com.aicarcompanion.app.handsfree.IncomingMessage
import com.aicarcompanion.app.handsfree.MessagingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Message notifications kept in memory only (at most [maxMessages], dropped when the notification
 * goes away or access is lost; never written to disk, logged, or sent to the AI). Replies use the
 * notification's own reply action: Penelope never uses SMS permissions, and only messages still in
 * the notification shade can be answered. The result is "Requested": the other app does the sending.
 */
class AndroidMessagingService(
    private val context: Context,
    private val maxMessages: Int = 20,
) : MessagingService {
    private val lock = Any()
    private val entries = LinkedHashMap<String, ParsedMessage>()
    private val _count = MutableStateFlow(0)
    override val unreadCount: StateFlow<Int> = _count.asStateFlow()
    private val _changes = MutableStateFlow(0L)
    override val changes: StateFlow<Long> = _changes.asStateFlow()

    fun onPosted(parsed: ParsedMessage) {
        synchronized(lock) {
            entries.remove(parsed.message.id)
            entries[parsed.message.id] = parsed
            while (entries.size > maxMessages) entries.remove(entries.keys.first())
            _count.value = entries.size
            _changes.value++
        }
    }

    fun onRemoved(key: String) {
        synchronized(lock) {
            entries.remove(key)
            _count.value = entries.size
            _changes.value++
        }
    }

    /** Access was turned off or the listener disconnected: forget everything. */
    fun clear() {
        synchronized(lock) {
            entries.clear()
            _count.value = 0
            _changes.value++
        }
    }

    override suspend fun unread(): List<IncomingMessage> = synchronized(lock) { entries.values.map { it.message } }

    override suspend fun reply(to: IncomingMessage, text: String, confirmed: Boolean): ActionResult {
        if (!confirmed) return ActionResult.NeedsConfirmation
        // The key must still belong to the same conversation: a notification can be reused by someone else.
        val handle = synchronized(lock) {
            entries[to.id]?.takeIf { it.message.conversationId == to.conversationId }?.reply
        } ?: return ActionResult.Failed(HandsFreeSafety.REPLY_GONE_TEXT)
        return try {
            handle.pendingIntent.send(context, 0, MessageNotificationParser.fillReply(handle, text))
            ActionResult.Requested
        } catch (_: PendingIntent.CanceledException) {
            ActionResult.Failed(HandsFreeSafety.REPLY_GONE_TEXT)
        }
    }
}
