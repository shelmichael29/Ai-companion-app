package com.aicarcompanion.app.handsfree

/**
 * Driver-safety rules every hands-free implementation must follow, on Android and iOS alike.
 *
 * - **Confirm before acting on someone else:** sending a message or calling someone is only done
 *   after Penelope repeats it and the user says yes. A misheard name must never call the wrong person.
 * - **Voice first:** while driving, results are spoken; nothing requires reading or tapping the screen.
 * - **Privacy:** message text and contact names are never logged, stored in history, or sent to
 *   the AI. They're spoken only when the user asks.
 * - **Vehicle safety first:** an urgent vehicle warning is never held back for media or messages.
 */
object HandsFreeSafety {
    fun requiresConfirmation(command: HandsFreeCommand): Boolean = when (command) {
        is HandsFreeCommand.SendMessage, is HandsFreeCommand.PlaceCall -> true
        else -> false
    }

    /** Whether the command's content (names, message text) may be sent to the AI provider. Never. */
    @Suppress("FunctionOnlyReturningConstant", "UNUSED_PARAMETER")
    fun mayShareWithAi(command: HandsFreeCommand): Boolean = false

    // ---- Confirmation ------------------------------------------------------------------------
    // Prompts never consist of an accepted word alone, so Penelope hearing herself can't confirm.

    private val YES = setOf("yes", "yeah", "yep", "send it", "yes send it", "confirm")
    private val YES_DIAL = setOf("dial")
    private val NO = setOf("no", "nope", "cancel", "stop", "never mind", "nevermind", "don't")

    private fun normalize(text: String) = text.lowercase().replace(Regex("[.,!?]"), "").trim().replace(Regex("\\s+"), " ")

    /** Only a clear yes confirms. Anything else cancels. */
    fun isAffirmative(utterance: String, forDial: Boolean): Boolean {
        val said = normalize(utterance)
        return said in YES || (forDial && said in YES_DIAL)
    }

    fun isCancel(utterance: String): Boolean = normalize(utterance) in NO

    const val DIAL_PROMPT_TEXT = "I can open your phone's dialer for this number. Should I go ahead?"
    const val ASK_REPLY_TEXT = "What would you like to say?"
    const val CONFIRM_REPLY_TEXT = "I have your reply ready. Do you want me to send this?"
    const val NOTHING_PENDING_TEXT = "There's nothing waiting to confirm."
    const val DIAL_OPENED_TEXT = "I asked your phone to open the dialer. Tap call to place the call."
    const val DIAL_FAILED_TEXT = "I couldn't open the phone's dialer."
    const val DIAL_CANCELLED_TEXT = "Okay, I didn't call."
    const val REPLY_CANCELLED_TEXT = "Okay, I didn't send it."
    const val REPLY_GONE_TEXT = "That message can't be replied to any more."
    const val NO_REPLYABLE_TEXT = "I can only reply to a message that's still in your notifications."
    const val NAME_CALL_TEXT = "I can't look up names yet. Tap Call someone on the Drive screen, say the number, or use your car's phone button."
    const val NO_MESSAGES_TEXT = "You have no new messages."
    const val NOT_CONNECTED_TEXT = "I can't see your notifications right now. Open Drive to reconnect, or switch notification access off and on in system settings."
    const val REPLY_TIMED_OUT_TEXT = "That timed out, so I didn't send it. Say reply to start again."
    const val DIAL_TIMED_OUT_TEXT = "That timed out, so I didn't call. Say the number to start again."
    const val NEED_REPLY_WORDS_TEXT = "I didn't catch a reply. What would you like to say?"

    fun replySentText(appName: String?) = "I passed your reply to ${appName ?: "the messaging app"} to send."

    /** "Sarah", or "Sarah in Family" for a group chat. */
    fun senderLabel(sender: String, group: String?) = if (group != null && group != sender) "$sender in $group" else sender

    /** Spoken only: digits are read one at a time. */
    fun dialPrompt(target: CallTarget): String {
        val who = target.displayName?.let { "$it at " }.orEmpty()
        return "Call $who${target.number.filter { it.isDigit() }.toList().joinToString(" ")}. Should I open the dialer?"
    }

    fun askReplyPrompt(recipient: String, group: String? = null) =
        if (group != null) "What would you like to say in $group?" else "What would you like to say to $recipient?"

    /** Ends "Do you want me to send this?" so no short ending of it is an accepted yes. */
    fun confirmReplyPrompt(recipient: String, text: String, group: String? = null) =
        (if (group != null) "Reply in $group: $text." else "Reply to $recipient: $text.") + " Do you want me to send this?"

    /** Penelope's honest reply when a capability can't be used yet. Generic: never echoes names or text. */
    fun unavailableReply(capability: HandsFreeCapability, status: CapabilityStatus): String = when {
        capability == HandsFreeCapability.VEHICLE_CONTROLS ->
            "I can't ${capability.spokenName}. ${com.aicarcompanion.app.vehiclecontrol.UnavailableReason.OBD_ADAPTER_READ_ONLY.explanation}"
        status is CapabilityStatus.NeedsSetup && status.requirements == listOf(PlatformRequirement.NOTIFICATION_LISTENER_CONNECTION) -> NOT_CONNECTED_TEXT
        else -> unavailableByStatus(capability, status)
    }

    private fun unavailableByStatus(capability: HandsFreeCapability, status: CapabilityStatus): String = when (status) {
        CapabilityStatus.Available -> "Okay."
        CapabilityStatus.NotImplemented ->
            "I can't ${capability.spokenName} yet. That's planned for a future update. You can use your phone or car controls for now."
        CapabilityStatus.NotSupported ->
            "This phone doesn't let me ${capability.spokenName}. Please use your phone or car controls."
        is CapabilityStatus.NeedsSetup ->
            "To ${capability.spokenName}, I need a quick setup on your phone first: " +
                status.requirements.joinToString("; ") { it.description.replaceFirstChar(Char::lowercase) } + "."
    }
}
