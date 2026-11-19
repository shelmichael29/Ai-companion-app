package com.aicarcompanion.app.handsfree

import com.aicarcompanion.app.assistant.conversation.AssistantReply
import com.aicarcompanion.app.assistant.conversation.AssistantRequest
import com.aicarcompanion.app.assistant.conversation.ReasoningProvider
import com.aicarcompanion.app.assistant.conversation.ReplyBasis

/**
 * Routes hands-free commands before the vehicle assistant and the AI see them. This stops the AI
 * from pretending ("Okay, calling Mom") and keeps names and message text away from it.
 *
 * Everything else goes to [delegate] unchanged, so diagnostics and conversation behave as before.
 * When a capability is built, its service is called here instead of the honest "not yet" reply.
 */
class HandsFreeReasoningProvider(
    private val delegate: ReasoningProvider,
    private val capabilities: CapabilityProvider,
    /** Privacy-safe diagnostics: receives only the command kind and the capability status. */
    private val onRouted: (commandKind: String, status: String) -> Unit = { _, _ -> },
) : ReasoningProvider {

    override suspend fun respond(request: AssistantRequest): AssistantReply {
        val command = VoiceCommandRouter.route(request.utterance) ?: return delegate.respond(request)
        val status = capabilities.status(command.capability)
        onRouted(command.logName, status.javaClass.simpleName)
        return AssistantReply(HandsFreeSafety.unavailableReply(command.capability, status), ReplyBasis.CONVERSATION)
    }

    override fun resetContext() = delegate.resetContext()
}
