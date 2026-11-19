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

    /** Penelope's honest reply when a capability can't be used yet. Generic: never echoes names or text. */
    fun unavailableReply(capability: HandsFreeCapability, status: CapabilityStatus): String = when (status) {
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
