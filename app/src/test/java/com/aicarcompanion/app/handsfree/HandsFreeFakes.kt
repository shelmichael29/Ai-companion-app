package com.aicarcompanion.app.handsfree

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Fakes for Drive tests: nothing touches media, messages or calls. */
class FakeMedia : MediaControl {
    override val nowPlaying = MutableStateFlow<NowPlaying?>(null)
    override val source = MutableStateFlow<MediaSource>(MediaSource.NoAccessKeysOnly)
    override val volume = MutableStateFlow<VolumeLevel?>(VolumeLevel(5, 15))
    val performed = mutableListOf<MediaAction>()
    val volumeChanges = mutableListOf<VolumeDirection>()
    var refreshes = 0
    override suspend fun perform(action: MediaAction): ActionResult { performed += action; return ActionResult.Requested }
    override suspend fun adjustVolume(direction: VolumeDirection): ActionResult {
        if (volume.value == null) return ActionResult.Failed("Volume is set by your car.")
        volumeChanges += direction
        return ActionResult.Done
    }
    override fun refresh() { refreshes++ }
}

class FakeMessaging(messages: List<IncomingMessage> = emptyList()) : MessagingService {
    val messages = messages.toMutableList()
    override val unreadCount = MutableStateFlow(messages.size)
    data class Sent(val id: String, val text: String, val confirmed: Boolean)
    val sent = mutableListOf<Sent>()
    var result: ActionResult = ActionResult.Requested
    override suspend fun unread(): List<IncomingMessage> = messages.toList()
    override suspend fun reply(to: IncomingMessage, text: String, confirmed: Boolean): ActionResult {
        sent += Sent(to.id, text, confirmed)
        return if (confirmed) result else ActionResult.NeedsConfirmation
    }
}

class FakeCalls : CallService {
    override val state: StateFlow<CallState> = MutableStateFlow(CallState.Idle)
    data class Dialed(val number: String, val confirmed: Boolean)
    val dialed = mutableListOf<Dialed>()
    override suspend fun dial(target: CallTarget, confirmed: Boolean): ActionResult {
        dialed += Dialed(target.number, confirmed)
        return if (confirmed) ActionResult.Requested else ActionResult.NeedsConfirmation
    }
    override suspend fun answer() = ActionResult.Unavailable(CapabilityStatus.NotImplemented)
    override suspend fun decline() = ActionResult.Unavailable(CapabilityStatus.NotImplemented)
    override suspend fun hangUp() = ActionResult.Unavailable(CapabilityStatus.NotImplemented)
}

class FakeClock(var now: Long = 0L) : () -> Long {
    override fun invoke() = now
}
