package com.aicarcompanion.app.handsfree

import com.aicarcompanion.app.assistant.conversation.AssistantReply
import com.aicarcompanion.app.assistant.conversation.ReplyBasis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Something waiting for the user's yes. Names, numbers and message text never appear in toString. */
sealed interface PendingAction {
    /** Waiting for the user to say what to reply. */
    data class Reply(val messageId: String, val recipient: String, val appName: String?) : PendingAction {
        override fun toString() = "PendingAction.Reply(redacted)"
    }
    /** Read back; waiting for yes or no. */
    data class ConfirmReply(val messageId: String, val recipient: String, val text: String, val appName: String? = null) : PendingAction {
        override fun toString() = "PendingAction.ConfirmReply(redacted)"
    }
    data class ConfirmDial(val target: CallTarget) : PendingAction {
        override fun toString() = "PendingAction.ConfirmDial(redacted)"
    }
}

/**
 * The one confirmation state machine for sending a reply or dialing. Voice ("yes") and the Drive
 * screen's Send/Cancel buttons both go through it, so nothing is sent without a clear yes, and
 * only once. Anything that isn't a clear yes cancels. It has no Android code.
 */
class HandsFreeDialog(
    private val messaging: MessagingService,
    private val calls: CallService,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) {
    private val lock = Any()
    private val _pending = MutableStateFlow<PendingAction?>(null)
    val pending: StateFlow<PendingAction?> = _pending.asStateFlow()
    private var proposedAt = 0L

    /** Starts a dial confirmation. Returns what Penelope says. */
    fun proposeDial(target: CallTarget): AssistantReply {
        set(PendingAction.ConfirmDial(target))
        return reply(HandsFreeSafety.DIAL_PROMPT_TEXT, HandsFreeSafety.dialPrompt(target))
    }

    /** Starts a reply. With [text] it goes straight to the read-back; without, Penelope asks what to say. */
    fun proposeReply(message: IncomingMessage, text: String?): AssistantReply {
        if (text.isNullOrBlank()) {
            set(PendingAction.Reply(message.id, message.senderDisplayName, message.appName))
            return reply(HandsFreeSafety.ASK_REPLY_TEXT, HandsFreeSafety.askReplyPrompt(message.senderDisplayName))
        }
        set(PendingAction.ConfirmReply(message.id, message.senderDisplayName, text.trim(), message.appName))
        return reply(HandsFreeSafety.CONFIRM_REPLY_TEXT, HandsFreeSafety.confirmReplyPrompt(message.senderDisplayName, text.trim()))
    }

    /**
     * Handles what the user said while something is pending. Returns null if nothing is pending
     * (the utterance is for the normal router). While pending, nothing reaches the AI.
     */
    suspend fun onUtterance(utterance: String): AssistantReply? {
        expireIfStale()
        val current = _pending.value ?: return null
        return when (current) {
            is PendingAction.Reply -> {
                if (HandsFreeSafety.isCancel(utterance)) return cancel()
                val text = utterance.trim()
                set(PendingAction.ConfirmReply(current.messageId, current.recipient, text, current.appName))
                reply(HandsFreeSafety.CONFIRM_REPLY_TEXT, HandsFreeSafety.confirmReplyPrompt(current.recipient, text))
            }
            is PendingAction.ConfirmReply -> if (HandsFreeSafety.isAffirmative(utterance, forDial = false)) confirm() else cancel()
            is PendingAction.ConfirmDial -> if (HandsFreeSafety.isAffirmative(utterance, forDial = true)) confirm() else cancel()
        }
    }

    /** The user confirmed (said yes or tapped Send/Dial). Calls the service once, then clears. */
    suspend fun confirm(): AssistantReply {
        val action = take() ?: return reply(HandsFreeSafety.NOTHING_PENDING_TEXT)
        return when (action) {
            is PendingAction.Reply -> reply(HandsFreeSafety.NOTHING_PENDING_TEXT)
            is PendingAction.ConfirmDial -> when (val r = calls.dial(action.target, confirmed = true)) {
                ActionResult.Requested, ActionResult.Done -> reply(HandsFreeSafety.DIAL_OPENED_TEXT)
                is ActionResult.Failed -> reply(r.reason)
                else -> reply(HandsFreeSafety.DIAL_FAILED_TEXT)
            }
            is PendingAction.ConfirmReply -> {
                val message = messaging.unread().firstOrNull { it.id == action.messageId && it.canReply }
                    ?: return reply(HandsFreeSafety.REPLY_GONE_TEXT)
                when (val r = messaging.reply(message, action.text, confirmed = true)) {
                    ActionResult.Requested, ActionResult.Done -> reply(HandsFreeSafety.replySentText(action.appName))
                    is ActionResult.Failed -> reply(r.reason)
                    else -> reply(HandsFreeSafety.REPLY_GONE_TEXT)
                }
            }
        }
    }

    /** The user said no or tapped Cancel. */
    fun cancel(): AssistantReply {
        val action = take() ?: return reply(HandsFreeSafety.NOTHING_PENDING_TEXT)
        return reply(
            when (action) {
                is PendingAction.ConfirmDial -> HandsFreeSafety.DIAL_CANCELLED_TEXT
                else -> HandsFreeSafety.REPLY_CANCELLED_TEXT
            },
        )
    }

    /** Drops a pending action that has waited too long. Safe to call often. */
    fun expireIfStale() {
        synchronized(lock) {
            if (_pending.value != null && clock() - proposedAt > timeoutMillis) _pending.value = null
        }
    }

    /** Clears everything (a new conversation, or the app leaving the foreground). */
    fun reset() = synchronized(lock) { _pending.value = null }

    private fun set(action: PendingAction) = synchronized(lock) {
        _pending.value = action
        proposedAt = clock()
    }

    private fun take(): PendingAction? = synchronized(lock) {
        val current = _pending.value
        _pending.value = null
        if (current != null && clock() - proposedAt > timeoutMillis) null else current
    }

    private fun reply(text: String, spoken: String = text) = AssistantReply(text, ReplyBasis.CONVERSATION, spokenText = spoken)

    companion object {
        const val TIMEOUT_MILLIS = 30_000L
    }
}
