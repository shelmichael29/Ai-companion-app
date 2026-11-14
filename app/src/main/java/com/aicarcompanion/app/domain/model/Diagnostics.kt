package com.aicarcompanion.app.domain.model

import java.time.Instant

/** A diagnostic trouble code (DTC) such as P0420, with a plain-language explanation if known. */
data class DiagnosticTroubleCode(
    val code: String,
    /** Technical definition, e.g. "System too lean (bank 1)". */
    val description: String,
    /** Plain-language explanation for everyday drivers. */
    val explanation: String? = null,
    /** Things a DIYer can check. */
    val diyChecks: String? = null,
    /** Technical detail for advanced users. */
    val technicalDetail: String? = null,
    /** Short plain-language title. */
    val headline: String? = null,
    val status: DtcStatus = DtcStatus.STORED,
    val severity: DtcSeverity = DtcSeverity.UNKNOWN,
    val origin: DataOrigin,
)

enum class DtcStatus(val label: String) {
    STORED("Stored"),
    PENDING("Pending"),
    PERMANENT("Permanent"),
}

/** How urgent a code usually is, in plain words. [advice] is what Penelope says. */
enum class DtcSeverity(val label: String, val advice: String) {
    INFO("Usually minor", "This is usually minor and fine to drive with, but it's worth fixing."),
    MODERATE("Get it checked soon", "It's usually OK to keep driving for now, but plan to get it checked soon."),
    SEVERE(
        "Get it checked promptly",
        "This one is important. If the check-engine light is flashing or the car runs roughly, drive gently and get it checked as soon as you can.",
    ),
    UNKNOWN("Not sure how serious", "I'm not sure how serious this one is, so it's worth having a mechanic take a look."),
}

/** A single sensor value (e.g. engine RPM) captured at [timestamp]. */
data class SensorReading(
    val name: String,
    val value: Double,
    val unit: String,
    val timestamp: Instant,
    val origin: DataOrigin,
    /** Standard OBD-II PID key (e.g. "010C") when read from a vehicle. */
    val pid: String? = null,
)
