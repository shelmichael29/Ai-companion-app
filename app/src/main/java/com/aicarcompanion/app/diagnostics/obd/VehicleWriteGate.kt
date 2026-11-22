package com.aicarcompanion.app.diagnostics.obd

import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The ONLY door to a vehicle-modifying command: Mode 04 "clear codes", owner decision 2026-11-21
 * (docs/CLEAR_CODES.md). [ReadOnlyCommandPolicy] is unchanged and still rejects "04" everywhere,
 * including the Advanced console.
 *
 * A [ClearCodesToken] is created by the confirmation UI after the user explicitly agrees. It is
 * one-shot, short-lived and bound to one vehicle and one code read. Redeeming it yields a
 * [ClearCodesPermit], which [Elm327Session.sendClearCodes] requires. Nothing else mints permits
 * (a source-scan test guards this).
 */
class ClearCodesToken internal constructor(
    internal val id: String,
    val vehicleId: String,
    /** The code read (this connection) the user saw before confirming. */
    val readSessionId: String,
    val expiresAt: Instant,
)

/** Proof that the gate approved one clear. Only [VehicleWriteGate] creates it. */
class ClearCodesPermit internal constructor(val vehicleId: String)

enum class GateRefusal { NO_TOKEN, EXPIRED, WRONG_VEHICLE, WRONG_READ_SESSION }

sealed interface GateDecision {
    data class Granted(val permit: ClearCodesPermit) : GateDecision
    data class Refused(val reason: GateRefusal) : GateDecision
}

class VehicleWriteGate(
    private val now: () -> Instant = Instant::now,
    private val timeToLive: Duration = Duration.ofSeconds(DEFAULT_TTL_SECONDS),
) {
    private var outstanding: ClearCodesToken? = null

    /** Issues a fresh token; any earlier unredeemed token stops working. */
    @Synchronized
    fun issue(vehicleId: String, readSessionId: String): ClearCodesToken =
        ClearCodesToken(UUID.randomUUID().toString(), vehicleId, readSessionId, now().plus(timeToLive))
            .also { outstanding = it }

    /**
     * Checks [token] against the vehicle and code read the app is acting on now. The token is
     * consumed by every attempt, successful or not, so it can never be replayed.
     */
    @Synchronized
    fun redeem(token: ClearCodesToken?, vehicleId: String, readSessionId: String): GateDecision {
        val live = outstanding
        outstanding = null
        return when {
            token == null || live == null || live.id != token.id -> GateDecision.Refused(GateRefusal.NO_TOKEN)
            !now().isBefore(live.expiresAt) -> GateDecision.Refused(GateRefusal.EXPIRED)
            live.vehicleId != vehicleId -> GateDecision.Refused(GateRefusal.WRONG_VEHICLE)
            live.readSessionId != readSessionId -> GateDecision.Refused(GateRefusal.WRONG_READ_SESSION)
            else -> GateDecision.Granted(ClearCodesPermit(vehicleId))
        }
    }

    /** Drops any outstanding token (dialog cancelled, connection lost, a precondition failed). */
    @Synchronized
    fun revoke() { outstanding = null }

    companion object {
        const val DEFAULT_TTL_SECONDS = 60L
    }
}
