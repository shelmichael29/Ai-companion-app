package com.aicarcompanion.app.domain.model

import java.time.Instant

/** A trouble code as it was read (or entered) at a specific time, kept per vehicle. */
data class RecordedTroubleCode(
    val id: Long = 0,
    val vehicleId: String,
    val code: String,
    val status: DtcStatus,
    val readAt: Instant,
    /** Codes read together share a session id, so "latest read" is well defined. */
    val readSessionId: String,
    val origin: DataOrigin,
)

/** A timestamped sensor value stored in history. */
data class RecordedSensorReading(
    val id: Long = 0,
    val vehicleId: String,
    val pid: String?,
    val name: String,
    val value: Double,
    val unit: String,
    val recordedAt: Instant,
    val origin: DataOrigin,
)

/**
 * Something noted about a vehicle. [kind] keeps verified facts apart from symptoms and
 * hypotheses so a future AI mechanic never presents a suspicion as a confirmed diagnosis.
 */
data class DiagnosticObservation(
    val id: String,
    val vehicleId: String,
    val createdAt: Instant,
    val kind: ObservationKind,
    val text: String,
    val relatedCode: String? = null,
    val origin: DataOrigin,
)

enum class ObservationKind(val label: String, val isVerified: Boolean) {
    /** Something the owner noticed, e.g. "rough idle when cold". */
    SYMPTOM("Symptom reported", isVerified = false),
    /** A free-form note from the owner. */
    NOTE("Note", isVerified = false),
    /** Confirmed by a measurement, inspection or repair (e.g. "coolant leak at water pump confirmed"). */
    VERIFIED_FINDING("Verified finding", isVerified = true),
    /** A possible cause that has not been confirmed. */
    HYPOTHESIS("Possible cause (unconfirmed)", isVerified = false),
}

data class MileageEntry(
    val id: Long = 0,
    val vehicleId: String,
    val odometerMiles: Int,
    val recordedAt: Instant,
    val origin: DataOrigin,
)

/** One trouble-code read. [codes] may be empty, which means "no codes found at [readAt]". */
data class TroubleCodeRead(
    val sessionId: String,
    val vehicleId: String,
    val readAt: Instant,
    val origin: DataOrigin,
    val codes: List<RecordedTroubleCode>,
)
