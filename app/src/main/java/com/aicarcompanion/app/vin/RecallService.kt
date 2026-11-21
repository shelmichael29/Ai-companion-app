package com.aicarcompanion.app.vin

import com.aicarcompanion.app.data.vehicledb.LookupError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/*
 * The one recall check for every car, however it was added (scanned VIN, typed VIN, catalog search
 * or manual entry) and wherever it is asked (the Add Vehicle screens and Penelope's answers).
 *
 * Rules this file enforces:
 * - A check that failed is never a check that found nothing. Every outcome is a typed
 *   [RecallLookupResult]; any unexpected error becomes [RecallLookupResult.Unavailable].
 * - Every report says where it came from and when it was made.
 * - A model-level campaign list (what NHTSA's public search can give) is kept apart from a
 *   VIN-specific status. Only an authoritative VIN source can produce [VinRecallStatus.Verified];
 *   today none is available, so the status is [VinRecallStatus.NotChecked].
 * - Nothing here looks at mileage, age, build date or sale date. The target carries only year,
 *   make and model, so no recall can be classed as ineligible from this code.
 */

/** What a recall check is about. Deliberately no VIN, mileage or dates. */
data class RecallTarget(val year: Int, val make: String, val model: String) {
    val displayName: String get() = "$year $make $model"

    /** The same car, ignoring capitalization and stray spaces ("BMW" and "bmw " are one make). */
    fun sameCarAs(other: RecallTarget): Boolean =
        year == other.year && make.trim().equals(other.make.trim(), ignoreCase = true) && model.trim().equals(other.model.trim(), ignoreCase = true)

    companion object {
        /** The target for a car's year, make and model, or null when any is missing. */
        fun of(year: Int?, make: String?, model: String?): RecallTarget? {
            val m = make?.trim().orEmpty()
            val mod = model?.trim().orEmpty()
            if (year == null || year !in 1900..2100 || m.isEmpty() || mod.isEmpty()) return null
            return RecallTarget(year, m, mod)
        }
    }
}

/** Whether a recall statement is about the car's model or confirmed for one VIN. */
enum class RecallScope { MODEL_LEVEL, VIN_VERIFIED }

/** The VIN-specific part of a report. Never inferred from the model-level list. */
sealed interface VinRecallStatus {
    /** No authoritative VIN-specific source was asked. Says nothing about this car. */
    data object NotChecked : VinRecallStatus

    /** An authoritative VIN source answered: [open] are the recalls it reports as not yet repaired. */
    data class Verified(val open: List<RecallCampaignInfo>, val source: String, val checkedAt: Instant) : VinRecallStatus

    /** The VIN source couldn't be reached. Not the same as "no open recalls". */
    data class Unavailable(val error: LookupError) : VinRecallStatus
}

/**
 * An authoritative source for the recalls still open on one specific VIN (NHTSA's own VIN lookup or a
 * manufacturer's). NHTSA's public API has no such lookup (its recalls-by-VIN address answers 403),
 * so the app ships none yet; this is where one would plug in.
 */
fun interface VinRecallSource {
    suspend fun open(vin: String, target: RecallTarget): VinRecallStatus
}

object NoVinRecallSource : VinRecallSource {
    override suspend fun open(vin: String, target: RecallTarget): VinRecallStatus = VinRecallStatus.NotChecked
}

/** The outcome of one check. [checkedAt] is when it was made (not when NHTSA published anything). */
data class RecallReport(
    val target: RecallTarget,
    /** Model-level campaigns for [target]. Not specific to any one car. */
    val result: RecallLookupResult,
    val checkedAt: Instant,
    val vin: VinRecallStatus = VinRecallStatus.NotChecked,
) {
    /** True only when the model-level lookup actually completed (found or none): never for a failure. */
    val completed: Boolean get() = result !is RecallLookupResult.Unavailable
}

interface RecallService {
    /**
     * Checks recalls for [target]. [vin] is given only when the car has one; it never goes to the
     * model-level lookup, only to a [VinRecallSource].
     */
    suspend fun check(target: RecallTarget, vin: String? = null): RecallReport
}

class DefaultRecallService(
    private val lookup: RecallLookup,
    private val vinSource: VinRecallSource = NoVinRecallSource,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val now: () -> Instant = Instant::now,
) : RecallService {
    override suspend fun check(target: RecallTarget, vin: String?): RecallReport {
        val result = try {
            // Never waits forever: a lookup that doesn't answer is a failed check, shown as such.
            withTimeoutOrNull(timeoutMillis) { lookup.forVehicle(target.year, target.make, target.model) }
                ?: RecallLookupResult.Unavailable(LookupError.OFFLINE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Whatever went wrong, it must read as "couldn't check", never as "nothing found".
            RecallLookupResult.Unavailable(LookupError.SERVICE)
        }
        val vinStatus = if (vin.isNullOrBlank()) {
            VinRecallStatus.NotChecked
        } else {
            try {
                withTimeoutOrNull(timeoutMillis) { vinSource.open(vin, target) } ?: VinRecallStatus.Unavailable(LookupError.OFFLINE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VinRecallStatus.Unavailable(LookupError.SERVICE)
            }
        }
        return RecallReport(target, result, now(), vinStatus)
    }

    private companion object {
        /** Longer than the HTTP connect plus read timeouts, so a slow network fails by itself first. */
        const val DEFAULT_TIMEOUT_MILLIS = 35_000L
    }
}
