package com.aicarcompanion.app.ui.drive

import com.aicarcompanion.app.handsfree.CallTarget
import com.aicarcompanion.app.handsfree.MediaSource
import com.aicarcompanion.app.handsfree.NowPlaying
import com.aicarcompanion.app.handsfree.PendingAction
import com.aicarcompanion.app.handsfree.VolumeLevel
import com.aicarcompanion.app.vehiclecontrol.NoVehicleControlProvider

/** Fixture states for Drive tests and screenshots (made-up names and titles, not real data). */
object DriveFixtures {
    val rows = vehicleControlRows(NoVehicleControlProvider, hasVehicle = true)

    val playingMedia = MediaUiState(
        MediaSource.Session("Sample Music"), NowPlaying("Sample Music", "Sample Song Title", "Sample Artist", isPlaying = true), VolumeLevel(7, 15),
    )
    val keysOnlyMedia = MediaUiState(MediaSource.NoAccessKeysOnly, null, VolumeLevel(7, 15))

    val playing = DriveUiState.Empty.copy(
        media = playingMedia, messages = MessagesUiState.None, vehicleControls = rows,
    )
    val needsAccess = DriveUiState.Empty.copy(media = keysOnlyMedia, messages = MessagesUiState.NeedsAccess, vehicleControls = rows)
    val unread = playing.copy(messages = MessagesUiState.Unread(2, "Sarah", "Messages"))
    val confirmReply = unread.copy(pending = PendingAction.ConfirmReply("k1", "Sarah", "Running ten minutes late", "Messages"))
    val confirmDial = playing.copy(pending = PendingAction.ConfirmDial(CallTarget("Sam Sample", "555 0100")))
}
