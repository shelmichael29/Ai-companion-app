package com.aicarcompanion.app.handsfree.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.aicarcompanion.app.handsfree.ActionResult
import com.aicarcompanion.app.handsfree.CallService
import com.aicarcompanion.app.handsfree.CallState
import com.aicarcompanion.app.handsfree.CallTarget
import com.aicarcompanion.app.handsfree.CapabilityStatus
import com.aicarcompanion.app.handsfree.HandsFreeSafety
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Calls by handing the number to the phone's dialer (ACTION_DIAL); the user taps call. Penelope
 * never has the call permission, so it can't place, answer or end calls itself.
 */
class AndroidCallService(private val context: Context) : CallService {
    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    override val state: StateFlow<CallState> = _state.asStateFlow()

    override suspend fun dial(target: CallTarget, confirmed: Boolean): ActionResult {
        if (!confirmed) return ActionResult.NeedsConfirmation
        val number = target.number.filter { it.isDigit() || it == '+' }
        if (number.none { it.isDigit() }) return ActionResult.Failed(HandsFreeSafety.DIAL_FAILED_TEXT)
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ActionResult.Requested
        } catch (_: ActivityNotFoundException) {
            ActionResult.Failed("This phone has no dialer app I can open.")
        } catch (_: SecurityException) {
            ActionResult.Failed(HandsFreeSafety.DIAL_FAILED_TEXT)
        }
    }

    // Answering, declining and hanging up need call permissions Penelope doesn't request. Deferred.
    override suspend fun answer() = ActionResult.Unavailable(CapabilityStatus.NotImplemented)
    override suspend fun decline() = ActionResult.Unavailable(CapabilityStatus.NotImplemented)
    override suspend fun hangUp() = ActionResult.Unavailable(CapabilityStatus.NotImplemented)
}
