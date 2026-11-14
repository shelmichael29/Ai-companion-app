package com.aicarcompanion.app.domain.model

import java.time.LocalDate

/** One entry in a vehicle's service history. */
data class MaintenanceRecord(
    val id: String,
    val vehicleId: String,
    val date: LocalDate,
    val title: String,
    val odometerMiles: Int? = null,
    val performedBy: String? = null,
    val notes: String? = null,
    val origin: DataOrigin,
)
