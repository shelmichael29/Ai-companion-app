package com.aicarcompanion.app.ui.drive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicarcompanion.app.assistant.VoiceSession
import com.aicarcompanion.app.handsfree.CallTarget
import com.aicarcompanion.app.handsfree.HandsFreeDialog
import com.aicarcompanion.app.handsfree.MediaAction
import com.aicarcompanion.app.handsfree.MediaControl
import com.aicarcompanion.app.handsfree.MessagingService
import com.aicarcompanion.app.handsfree.VolumeDirection
import com.aicarcompanion.app.handsfree.android.NotificationAccessState
import com.aicarcompanion.app.ui.home.EverydayHealth
import com.aicarcompanion.app.vehiclecontrol.VehicleControlProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State and actions for the Drive screen. It talks only to the hands-free services (media,
 * messages, the confirmation dialog) and the voice session. It has no vehicle link: it reads
 * health from [health] (already computed) and can't send anything to the car.
 */
class DriveViewModel(
    private val media: MediaControl,
    private val messaging: MessagingService,
    private val dialog: HandsFreeDialog,
    private val access: NotificationAccessState,
    private val vehicleControls: VehicleControlProvider,
    val session: VoiceSession,
    health: Flow<EverydayHealth?>,
    hasVehicle: Flow<Boolean>,
    private val dialerAvailable: () -> Boolean = { true },
    /** Speaks a confirmation prompt when the on-screen flow started it (does nothing during a conversation). */
    private val speak: suspend (String) -> Unit = {},
) : ViewModel() {

    private val notice = MutableStateFlow<String?>(null)

    private val mediaState = combine(media.source, media.nowPlaying, media.volume, ::MediaUiState)

    private val messagesState = combine(access.granted, messaging.unreadCount) { granted, count -> granted to count }
        .map { (granted, count) ->
            val latest = if (granted && count > 0) messaging.unread().lastOrNull() else null
            messagesState(granted, count, latest?.senderDisplayName, latest?.appName)
        }

    private val voiceState = combine(session.state, session.conversationActive, session.inputLevel) { s, a, l -> Triple(s, a, l) }

    private val healthAndVehicle = combine(health, hasVehicle) { h, v -> h to v }

    val state: StateFlow<DriveUiState> = combine(
        mediaState, messagesState, dialog.pending, combine(healthAndVehicle, notice) { hv, n -> hv to n }, voiceState,
    ) { m, msgs, pending, (hv, note), (voice, active, level) ->
        DriveUiState(
            media = m,
            messages = msgs,
            calls = if (dialerAvailable()) CallsUiState.DIAL_HANDOFF else CallsUiState.NO_DIALER,
            pending = pending,
            vehicleControls = vehicleControlRows(vehicleControls, hv.second),
            health = hv.first,
            voice = voice,
            conversationActive = active,
            micLevel = level,
            notice = note,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DriveUiState.Empty)

    fun play() = perform(MediaAction.PLAY)
    fun pause() = perform(MediaAction.PAUSE)
    fun next() = perform(MediaAction.NEXT)
    fun previous() = perform(MediaAction.PREVIOUS)

    private fun perform(action: MediaAction) {
        viewModelScope.launch {
            notice.value = when (val r = media.perform(action)) {
                com.aicarcompanion.app.handsfree.ActionResult.Requested -> "I asked your media app to ${verb(action)}."
                is com.aicarcompanion.app.handsfree.ActionResult.Failed -> r.reason
                else -> null
            }
        }
    }

    private fun verb(action: MediaAction) = when (action) {
        MediaAction.PLAY -> "play"
        MediaAction.PAUSE -> "pause"
        MediaAction.NEXT -> "skip to the next track"
        MediaAction.PREVIOUS -> "go back a track"
    }

    fun volume(direction: VolumeDirection) {
        viewModelScope.launch {
            val r = media.adjustVolume(direction)
            notice.value = (r as? com.aicarcompanion.app.handsfree.ActionResult.Failed)?.reason
        }
    }

    /** The user chose a contact in the system picker. Starts the same confirmation as voice. */
    fun onContactPicked(target: CallTarget) {
        val reply = dialog.proposeDial(target)
        notice.value = null
        viewModelScope.launch { speak(reply.spokenText) }
    }

    fun confirm() {
        viewModelScope.launch { notice.value = dialog.confirm().text }
    }

    fun cancel() {
        notice.value = dialog.cancel().text
    }

    /** Asks Penelope by voice pipeline, so the same rules and confirmation apply as when spoken. */
    fun ask(utterance: String) = session.submitText(utterance)

    fun onResume() {
        access.refresh()
        media.refresh()
        dialog.expireIfStale()
    }

    fun expireStale() = dialog.expireIfStale()
}

/** The plain-language car health summary for the DIY card (reuses the Everyday wording; read-only). */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun driveHealthFlow(
    vehicles: com.aicarcompanion.app.domain.repository.VehicleRepository,
    diagnostics: com.aicarcompanion.app.domain.repository.DiagnosticsRepository,
    history: com.aicarcompanion.app.domain.repository.VehicleHistoryRepository,
    obdState: Flow<com.aicarcompanion.app.diagnostics.obd.ObdConnectionState>,
): Flow<EverydayHealth?> = vehicles.activeVehicleId.flatMapLatest { id ->
    if (id == null) {
        obdState.map { obd -> com.aicarcompanion.app.ui.home.everydayHealth(null, emptyList(), null, obd) }
    } else {
        combine(vehicles.observeVehicle(id), diagnostics.troubleCodes(id), history.latestTroubleCodeRead(id), obdState) { v, codes, read, obd ->
            com.aicarcompanion.app.ui.home.everydayHealth(v, codes, read, obd)
        }
    }
}
