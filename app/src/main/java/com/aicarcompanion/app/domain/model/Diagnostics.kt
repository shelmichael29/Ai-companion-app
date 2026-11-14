package com.aicarcompanion.app.domain.model

import java.time.Instant

/** A diagnostic trouble code (DTC) such as P0420, with a plain-language explanation if known. */
data class DiagnosticTroubleCode(
    val code: String,
    val description: String,
    val explanation: String? = null,
    val status: DtcStatus = DtcStatus.STORED,
    val severity: DtcSeverity = DtcSeverity.UNKNOWN,
    val origin: DataOrigin,
)

enum class DtcStatus(val label: String) {
    STORED("Stored"),
    PENDING("Pending"),
    PERMANENT("Permanent"),
}

enum class DtcSeverity(val label: String) {
    INFO("Informational"),
    MODERATE("Should be checked soon"),
    SEVERE("Stop and get help"),
    UNKNOWN("Severity unknown"),
}

/** A single sensor value (e.g. engine RPM) captured at [timestamp]. */
data class SensorReading(
    val name: String,
    val value: Double,
    val unit: String,
    val timestamp: Instant,
    val origin: DataOrigin,
)
