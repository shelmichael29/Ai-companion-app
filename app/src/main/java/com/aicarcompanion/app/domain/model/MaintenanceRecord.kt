package com.aicarcompanion.app.domain.model

import java.time.LocalDate

/** One entry in a vehicle's service history: routine maintenance or a repair. */
data class MaintenanceRecord(
    val id: String,
    val vehicleId: String,
    val date: LocalDate,
    val title: String,
    val kind: ServiceKind = ServiceKind.MAINTENANCE,
    val odometerMiles: Int? = null,
    val performedBy: String? = null,
    val costCents: Long? = null,
    val notes: String? = null,
    val origin: DataOrigin,
)

enum class ServiceKind(val label: String) {
    MAINTENANCE("Maintenance"),
    REPAIR("Repair"),
}
