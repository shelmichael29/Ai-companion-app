package com.aicarcompanion.app.mechanic

import com.aicarcompanion.app.domain.model.DiagnosticObservation
import com.aicarcompanion.app.domain.model.MaintenanceRecord
import com.aicarcompanion.app.domain.model.TroubleCodeRead
import com.aicarcompanion.app.domain.model.Vehicle
import java.time.Instant

/*
 * Future interoperability with "Penelope Mechanic Pro" (a separate app/service). Interfaces
 * and data contracts only; nothing here contacts a server. Keeping the contract here, rather
 * than sharing code with the marketplace, keeps the two apps loosely coupled.
 */

/** What the user agreed to share, and when. Nothing is shared without it. */
data class ShareConsent(
    val includeVehicle: Boolean,
    val includeTroubleCodes: Boolean,
    val includeHistory: Boolean,
    val includeNotes: Boolean,
    val grantedAt: Instant,
)

/** A mechanic handoff report: facts with their origins; hypotheses stay labeled as such. */
data class MechanicHandoffReport(
    val vehicle: Vehicle,
    val latestCodes: TroubleCodeRead?,
    val serviceHistory: List<MaintenanceRecord>,
    val notes: List<DiagnosticObservation>,
    val summary: String,
    val createdAt: Instant,
)

data class MechanicSearchCriteria(
    val vehicleMake: String,
    val needs: List<String>,
    val mobileServiceWanted: Boolean = false,
)

data class MechanicProfile(
    val id: String,
    val displayName: String,
    val verifiedQualifications: List<String>,
    val averageRating: Double?,
    val reviewCount: Int,
    val offersMobileService: Boolean,
)

sealed interface MechanicNetworkResult<out T> {
    data class Success<T>(val value: T) : MechanicNetworkResult<T>
    data class Unavailable(val reason: String) : MechanicNetworkResult<Nothing>
}

interface MechanicNetwork {
    suspend fun findMechanics(criteria: MechanicSearchCriteria): MechanicNetworkResult<List<MechanicProfile>>
    suspend fun sendRequest(mechanicId: String, report: MechanicHandoffReport, consent: ShareConsent): MechanicNetworkResult<String>
}

/** Shipped default until Penelope Mechanic Pro exists. */
object MechanicNetworkNotAvailable : MechanicNetwork {
    private const val REASON = "Mechanic search isn't available yet."
    override suspend fun findMechanics(criteria: MechanicSearchCriteria) = MechanicNetworkResult.Unavailable(REASON)
    override suspend fun sendRequest(mechanicId: String, report: MechanicHandoffReport, consent: ShareConsent) =
        MechanicNetworkResult.Unavailable(REASON)
}
