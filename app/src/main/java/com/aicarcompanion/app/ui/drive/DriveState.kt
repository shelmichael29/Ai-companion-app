package com.aicarcompanion.app.ui.drive

import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.handsfree.MediaSource
import com.aicarcompanion.app.handsfree.NowPlaying
import com.aicarcompanion.app.handsfree.PendingAction
import com.aicarcompanion.app.handsfree.VolumeLevel
import com.aicarcompanion.app.ui.home.EverydayHealth
import com.aicarcompanion.app.vehiclecontrol.UnavailableReason
import com.aicarcompanion.app.vehiclecontrol.VehicleControlAvailability
import com.aicarcompanion.app.vehiclecontrol.VehicleControlFeature
import com.aicarcompanion.app.vehiclecontrol.VehicleControlProvider

/*
 * What the Drive screen shows. Pure Kotlin (no Android), so the mapping is unit-tested and an iOS
 * app can mirror it. Nothing here holds message bodies; names and track titles are shown only on
 * screen and never logged (their types redact toString).
 */

data class MediaUiState(val source: MediaSource, val nowPlaying: NowPlaying?, val volume: VolumeLevel?)

sealed interface MessagesUiState {
    /** Notification access is off: Penelope can't see or read messages. */
    data object NeedsAccess : MessagesUiState
    data object None : MessagesUiState
    data class Unread(val count: Int, val latestSender: String?, val appName: String?) : MessagesUiState {
        override fun toString() = "Unread(count=$count)"
    }
}

/** Calls are a handoff to the phone's dialer; the user taps call. */
enum class CallsUiState { DIAL_HANDOFF, NO_DIALER }

data class VehicleControlRow(val feature: VehicleControlFeature, val reason: UnavailableReason)

data class DriveUiState(
    val media: MediaUiState,
    val messages: MessagesUiState,
    val calls: CallsUiState,
    val pending: PendingAction?,
    val vehicleControls: List<VehicleControlRow>,
    val health: EverydayHealth?,
    val voice: VoiceState,
    val conversationActive: Boolean,
    val micLevel: Float,
    /** The honest result of the last button or voice action, e.g. "I asked your media app to pause." */
    val notice: String?,
) {
    companion object {
        val Empty = DriveUiState(
            media = MediaUiState(MediaSource.NoAccessKeysOnly, null, null),
            messages = MessagesUiState.NeedsAccess,
            calls = CallsUiState.DIAL_HANDOFF,
            pending = null,
            vehicleControls = emptyList(),
            health = null,
            voice = VoiceState.Idle,
            conversationActive = false,
            micLevel = 0f,
            notice = null,
        )
    }
}

fun messagesState(accessGranted: Boolean, count: Int, latestSender: String?, appName: String?): MessagesUiState = when {
    !accessGranted -> MessagesUiState.NeedsAccess
    count <= 0 -> MessagesUiState.None
    else -> MessagesUiState.Unread(count, latestSender, appName)
}

/** Every feature, each with its reason. All are unavailable: there is no way to control the car. */
fun vehicleControlRows(provider: VehicleControlProvider, hasVehicle: Boolean): List<VehicleControlRow> =
    VehicleControlFeature.entries.map { feature ->
        when (val a = provider.availability(feature, hasVehicle)) {
            is VehicleControlAvailability.Unavailable -> VehicleControlRow(feature, a.reason)
        }
    }

/** Words under the media controls. Never implies universal control. */
fun MediaUiState.sourceLine(): String = when (source) {
    MediaSource.NoAccessKeysOnly -> "Turn on notification access to see what's playing."
    MediaSource.NoActiveSession -> "No media app is playing right now."
    is MediaSource.Session -> source.appName?.let { "Playing in $it" } ?: "Playing in your media app"
}

/** Honest note: buttons ask the media app, which may or may not comply. */
const val MEDIA_HONESTY = "These buttons ask your media app. Some apps don't respond."
