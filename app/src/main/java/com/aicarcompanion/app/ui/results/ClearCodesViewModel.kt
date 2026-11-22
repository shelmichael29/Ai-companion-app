package com.aicarcompanion.app.ui.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.SettingsStore
import com.aicarcompanion.app.diagnostics.obd.ClearCodesRefusal
import com.aicarcompanion.app.diagnostics.obd.ClearCodesResult
import com.aicarcompanion.app.diagnostics.obd.ClearCodesToken
import com.aicarcompanion.app.diagnostics.obd.ClearCodesTokenResult
import com.aicarcompanion.app.diagnostics.obd.CodeReadSession
import com.aicarcompanion.app.diagnostics.obd.ObdConnection
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.VehicleCodeClearing
import com.aicarcompanion.app.domain.repository.VehicleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Whether the "Clear codes…" button is shown, and if it is disabled, why. */
data class ClearCodesAvailability(val visible: Boolean, val enabled: Boolean, val disabledReason: String?) {
    companion object {
        const val READ_FIRST = "Read codes first"

        /** Never shown at the Everyday level. Enabled only after codes were read this connection. */
        fun of(
            level: ExplanationLevel,
            readSession: CodeReadSession?,
            activeVehicleId: String?,
            connected: Boolean,
        ): ClearCodesAvailability = when {
            level == ExplanationLevel.EVERYDAY -> ClearCodesAvailability(false, false, null)
            !connected -> ClearCodesAvailability(true, false, "Connect to your car first")
            activeVehicleId == null || readSession?.vehicleId != activeVehicleId ->
                ClearCodesAvailability(true, false, READ_FIRST)
            else -> ClearCodesAvailability(true, true, null)
        }
    }
}

data class ClearCodesDialogState(
    val open: Boolean = false,
    val understood: Boolean = false,
    val working: Boolean = false,
    /** A short problem to show inside the dialog (e.g. confirmation expired). */
    val problem: String? = null,
    val result: ClearCodesResult? = null,
)

data class ClearCodesUiState(
    val availability: ClearCodesAvailability = ClearCodesAvailability(false, false, null),
    val dialog: ClearCodesDialogState = ClearCodesDialogState(),
)

/** Plain-words outcome. "Codes cleared" only when the car answered positively. */
fun ClearCodesResult.summary(): String = when (this) {
    is ClearCodesResult.Cleared -> when {
        remaining == null -> "Codes cleared. I couldn't re-read the codes to double-check; read them again to verify."
        remaining == 0 -> "Codes cleared. A new read shows no codes. The check-engine light should go out, and the car's readiness monitors need to re-run."
        else -> "The car accepted the request, but $remaining code(s) are still reported after re-reading. The fault may still be present."
    }
    is ClearCodesResult.NotAccepted -> "The car did not accept the request, so nothing was cleared." + remainingText(remaining)
    is ClearCodesResult.NoAnswer -> "The car didn't answer, so I can't tell whether anything was cleared." + remainingText(remaining)
    is ClearCodesResult.Refused -> reason.message
}

private fun remainingText(remaining: Int?) = when (remaining) {
    null -> " I couldn't re-read the codes."
    0 -> " A new read shows no codes."
    else -> " A new read shows $remaining code(s)."
}

/**
 * Drives the Clear codes button and its two-step confirmation. The token is requested only when the
 * user ticks "I understand" and is spent only by "Clear codes"; the OBD layer re-checks every rule.
 */
class ClearCodesViewModel(
    private val clearing: VehicleCodeClearing,
    private val vehicles: VehicleRepository,
    private val settings: SettingsStore,
    obd: ObdConnection,
) : ViewModel() {

    private val dialog = MutableStateFlow(ClearCodesDialogState())
    private var token: ClearCodesToken? = null
    /** Bumped whenever a pending token request stops being wanted (untick, reopen, dismiss). */
    private var issueGeneration = 0

    val state: StateFlow<ClearCodesUiState> = combine(
        settings.settings, clearing.codeReadSession, vehicles.activeVehicleId, obd.state, dialog,
    ) { s, read, vehicleId, conn, d ->
        ClearCodesUiState(
            ClearCodesAvailability.of(s.explanationLevel, read, vehicleId, conn is ObdConnectionState.Connected), d,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClearCodesUiState())

    fun open() {
        if (!state.value.availability.enabled) return
        issueGeneration++
        discardToken()
        dialog.value = ClearCodesDialogState(open = true)
    }

    /** Ticking "I understand" is the confirmation: it asks the OBD layer for a short-lived token. */
    fun setUnderstood(understood: Boolean) {
        val d = dialog.value
        if (!d.open || d.working || d.result != null) return
        if (!understood) {
            issueGeneration++
            discardToken()
            dialog.value = d.copy(understood = false, problem = null)
            return
        }
        val generation = ++issueGeneration
        viewModelScope.launch {
            val vehicleId = vehicles.activeVehicleId.first()
            val issued = if (vehicleId == null) null else clearing.issueClearCodesToken(vehicleId)
            if (generation != issueGeneration || !dialog.value.open) {
                // The user unticked, reopened or cancelled while this was pending: never keep the token.
                if (issued is ClearCodesTokenResult.Issued) clearing.cancelClearCodes()
                return@launch
            }
            when (issued) {
                is ClearCodesTokenResult.Issued -> {
                    token = issued.token
                    dialog.update { it.copy(understood = true, problem = null) }
                }
                is ClearCodesTokenResult.Refused -> dialog.update { it.copy(understood = false, problem = issued.reason.message) }
                null -> dialog.update { it.copy(understood = false, problem = ClearCodesRefusal.CODES_NOT_READ.message) }
            }
        }
    }

    fun confirm() {
        val d = dialog.value
        val t = token ?: return
        if (!d.open || !d.understood || d.working || d.result != null) return
        token = null // one shot: spent whether or not it works
        dialog.value = d.copy(working = true, problem = null)
        viewModelScope.launch {
            val vehicleId = vehicles.activeVehicleId.first()
            val result = if (vehicleId == null) ClearCodesResult.Refused(ClearCodesRefusal.CODES_NOT_READ)
            else clearing.clearCodes(vehicleId, t)
            dialog.update {
                if (result is ClearCodesResult.Refused) it.copy(working = false, understood = false, problem = result.reason.message)
                else it.copy(working = false, result = result)
            }
        }
    }

    /** Cancel, or "Done" after a result. Ignored while a clear is running. */
    fun dismiss() {
        if (dialog.value.working) return
        issueGeneration++
        discardToken()
        dialog.value = ClearCodesDialogState()
    }

    private fun discardToken() {
        token = null
        clearing.cancelClearCodes()
    }
}
