package com.aicarcompanion.app.diagnostics.obd

import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/** A successful code read (Mode 03 stored) of one vehicle during the current adapter connection. */
data class CodeReadSession(val vehicleId: String, val sessionId: String, val readAt: Instant)

/** Why a clear was not sent. [message] is shown to the user. */
enum class ClearCodesRefusal(val message: String) {
    LEVEL_NOT_ALLOWED("Clearing codes is only available at the DIY and Advanced knowledge levels."),
    NOT_CONNECTED("Not connected to the adapter."),
    VEHICLE_NOT_RESPONDING("The car isn't responding. Turn the ignition on, then try again."),
    CODES_NOT_READ("Read codes first."),
    ENGINE_RUNNING("The engine appears to be running. Switch it off, leave the ignition on, then try again."),
    ENGINE_STATE_UNKNOWN("Couldn't confirm that the engine is off. Switch it off, leave the ignition on, then try again."),
    NOT_CONFIRMED("Please confirm again."),
    CONFIRMATION_EXPIRED("The confirmation timed out. Please confirm again."),
    WRONG_VEHICLE("This confirmation was for a different vehicle. Please confirm again."),
    CODES_CHANGED("The codes were read again since you confirmed. Please confirm again."),
    BUSY("A clear is already in progress."),
}

sealed interface ClearCodesTokenResult {
    data class Issued(val token: ClearCodesToken) : ClearCodesTokenResult
    data class Refused(val reason: ClearCodesRefusal) : ClearCodesTokenResult
}

/**
 * What happened. "Codes cleared" may only be said for [Cleared]: the car answered the clear request
 * positively. [remaining] is the number of codes the re-read afterwards still reports (null if the
 * re-read failed, so the result couldn't be verified).
 */
sealed interface ClearCodesResult {
    data class Cleared(val remaining: Int?) : ClearCodesResult
    /** The car answered, but not with a positive response. */
    data class NotAccepted(val remaining: Int?) : ClearCodesResult
    /** No answer in time, or the link dropped: it is unknown whether anything was cleared. */
    data class NoAnswer(val remaining: Int?) : ClearCodesResult
    data class Refused(val reason: ClearCodesRefusal) : ClearCodesResult
}

/**
 * The Clear-codes capability of the OBD layer. The confirmation UI first asks for a token (after
 * the user's explicit "I understand"), then spends it on [clearCodes]. All preconditions are
 * enforced here, not only in the UI.
 */
interface VehicleCodeClearing {
    /** The code read this connection that Clear codes depends on; null until codes are read. */
    val codeReadSession: StateFlow<CodeReadSession?>

    /** Called by the confirmation UI. The token lasts about 60 seconds and works once. */
    fun issueClearCodesToken(vehicleId: String): ClearCodesTokenResult

    /** Sends Mode 04 if every rule holds, re-reads the codes and records the outcome in history. */
    suspend fun clearCodes(vehicleId: String, token: ClearCodesToken): ClearCodesResult

    /** The user cancelled the dialog: forget any token. */
    fun cancelClearCodes()
}
