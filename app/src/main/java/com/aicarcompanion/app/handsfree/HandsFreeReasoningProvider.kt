package com.aicarcompanion.app.handsfree

import com.aicarcompanion.app.assistant.conversation.AssistantReply
import com.aicarcompanion.app.assistant.conversation.AssistantRequest
import com.aicarcompanion.app.assistant.conversation.ReasoningProvider
import com.aicarcompanion.app.assistant.conversation.ReplyBasis

/**
 * Routes hands-free commands before the vehicle assistant and the AI see them. This stops the AI
 * from pretending ("Okay, calling Mom") and keeps names and message text away from it.
 *
 * Order: something waiting for a yes gets the utterance first (it never reaches the router or the
 * AI), then the command router, then [delegate] unchanged, so diagnostics and conversation behave
 * as before. A capability's service is only called when [capabilities] says it is Available.
 *
 * Transcript text ([AssistantReply.text]) never contains message bodies, numbers or contact names
 * beyond a sender's name; what's read aloud is in [AssistantReply.spokenText].
 */
class HandsFreeReasoningProvider(
    private val delegate: ReasoningProvider,
    private val capabilities: CapabilityProvider,
    private val media: MediaControl? = null,
    private val messaging: MessagingService? = null,
    private val dialog: HandsFreeDialog? = null,
    /** Privacy-safe diagnostics: receives only the command kind and the capability status. */
    private val onRouted: (commandKind: String, status: String) -> Unit = { _, _ -> },
) : ReasoningProvider {

    override suspend fun respond(request: AssistantRequest): AssistantReply {
        dialog?.onUtterance(request.utterance)?.let { return it }
        val command = VoiceCommandRouter.route(request.utterance) ?: return delegate.respond(request)
        val status = capabilities.status(command.capability)
        onRouted(command.logName, status.javaClass.simpleName)
        if (status != CapabilityStatus.Available) return say(HandsFreeSafety.unavailableReply(command.capability, status))
        return when (command) {
            is HandsFreeCommand.Media -> media?.let { mediaReply(command.action, it.perform(command.action)) } ?: notYet(command)
            is HandsFreeCommand.Volume -> media?.let { volumeReply(command.direction, it.adjustVolume(command.direction)) } ?: notYet(command)
            HandsFreeCommand.ReadMessages -> messaging?.let { readLatest(it) } ?: notYet(command)
            is HandsFreeCommand.SendMessage -> if (messaging != null && dialog != null) startReply(command, messaging, dialog) else notYet(command)
            is HandsFreeCommand.PlaceCall -> if (dialog != null) startCall(command, dialog) else notYet(command)
            else -> notYet(command)
        }
    }

    override fun resetContext() {
        dialog?.reset()
        delegate.resetContext()
    }

    private fun say(text: String, spoken: String = text) = AssistantReply(text, ReplyBasis.CONVERSATION, spokenText = spoken)

    private fun notYet(command: HandsFreeCommand) =
        say(HandsFreeSafety.unavailableReply(command.capability, CapabilityStatus.NotImplemented))

    private fun mediaReply(action: MediaAction, result: ActionResult): AssistantReply = when (result) {
        ActionResult.Requested -> say("I asked your media app to ${verb(action)}.")
        ActionResult.Done -> say("Okay.")
        is ActionResult.Failed -> say(result.reason)
        is ActionResult.Unavailable -> say(HandsFreeSafety.unavailableReply(HandsFreeCapability.MEDIA_CONTROL, result.status))
        ActionResult.NeedsConfirmation -> say("Okay.")
    }

    private fun verb(action: MediaAction) = when (action) {
        MediaAction.PLAY -> "play"
        MediaAction.PAUSE -> "pause"
        MediaAction.NEXT -> "skip to the next track"
        MediaAction.PREVIOUS -> "go back a track"
    }

    private fun volumeReply(direction: VolumeDirection, result: ActionResult): AssistantReply = when (result) {
        is ActionResult.Failed -> say(result.reason)
        is ActionResult.Unavailable -> say(HandsFreeSafety.unavailableReply(HandsFreeCapability.MEDIA_CONTROL, result.status))
        else -> say(if (direction == VolumeDirection.UP) "Okay, volume up." else "Okay, volume down.")
    }

    private suspend fun readLatest(service: MessagingService): AssistantReply {
        val latest = service.unread().lastOrNull() ?: return say(HandsFreeSafety.NO_MESSAGES_TEXT)
        val app = latest.appName?.let { " on $it" }.orEmpty()
        val who = HandsFreeSafety.senderLabel(latest.senderDisplayName, latest.groupTitle)
        val body = latest.text.take(MAX_READ_CHARS)
        val tail = if (latest.canReply) " Say reply to answer." else ""
        return say(
            text = "I read 1 message from $who aloud.",
            spoken = "$who$app: $body.$tail",
        )
    }

    private suspend fun startReply(command: HandsFreeCommand.SendMessage, service: MessagingService, dialog: HandsFreeDialog): AssistantReply {
        val candidates = service.unread().filter { it.canReply }
        val target = command.recipient
            ?.let { name -> candidates.lastOrNull { it.senderDisplayName.equals(name, ignoreCase = true) || it.groupTitle.equals(name, ignoreCase = true) } }
            ?: if (command.recipient == null) candidates.lastOrNull() else null
        return if (target == null) say(HandsFreeSafety.NO_REPLYABLE_TEXT) else dialog.proposeReply(target, command.body)
    }

    private fun startCall(command: HandsFreeCommand.PlaceCall, dialog: HandsFreeDialog): AssistantReply {
        val number = spokenNumber(command.contact.orEmpty()) ?: return say(HandsFreeSafety.NAME_CALL_TEXT)
        return dialog.proposeDial(CallTarget(null, number))
    }

    /** Returns the digits if [said] is a phone number ("555 123 4567", "+1 (555) 123-4567"), else null. */
    private fun spokenNumber(said: String): String? {
        if (!said.all { it.isDigit() || it in " -()+." }) return null
        val digits = said.filter { it.isDigit() || it == '+' }
        return digits.takeIf { it.count(Char::isDigit) in MIN_DIGITS..MAX_DIGITS }
    }

    private companion object {
        const val MAX_READ_CHARS = 300
        const val MIN_DIGITS = 3
        const val MAX_DIGITS = 15
    }
}
