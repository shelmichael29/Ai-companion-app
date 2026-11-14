package com.aicarcompanion.app.data.sample

import com.aicarcompanion.app.diagnostics.dtc.GenericDtcCatalog
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DiagnosticTroubleCode
import com.aicarcompanion.app.domain.model.DtcStatus
import com.aicarcompanion.app.domain.model.MaintenanceRecord
import com.aicarcompanion.app.domain.model.SensorReading
import java.time.Instant
import java.time.LocalDate

/**
 * Demonstration diagnostics shown only when the user turns on demo data in Settings. Every diagnostic and maintenance item is tagged [DataOrigin.SAMPLE].
 * Values are fixed (not simulated over time) to avoid looking like a live vehicle feed.
 */
object SampleData {

    private val capturedAt: Instant = Instant.parse("2026-01-01T12:00:00Z")

    fun troubleCodes(): List<DiagnosticTroubleCode> = listOf("P0133", "P0442").mapNotNull { code ->
        GenericDtcCatalog.lookup(code)?.let { info ->
            DiagnosticTroubleCode(
                code = info.code,
                description = info.description,
                explanation = info.explanation,
                status = if (code == "P0442") DtcStatus.PENDING else DtcStatus.STORED,
                severity = info.severity,
                origin = DataOrigin.SAMPLE,
            )
        }
    }

    fun sensorReadings(): List<SensorReading> = listOf(
        SensorReading("Engine speed", 780.0, "rpm", capturedAt, DataOrigin.SAMPLE),
        SensorReading("Coolant temperature", 90.0, "°C", capturedAt, DataOrigin.SAMPLE),
        SensorReading("Control module voltage", 14.1, "V", capturedAt, DataOrigin.SAMPLE),
        SensorReading("Fuel level", 62.0, "%", capturedAt, DataOrigin.SAMPLE),
    )

    fun maintenance(vehicleId: String): List<MaintenanceRecord> = listOf(
        MaintenanceRecord(
            id = "$vehicleId-oil", vehicleId = vehicleId, date = LocalDate.of(2025, 9, 14),
            title = "Oil and filter change", odometerMiles = 61_800,
            performedBy = "Sample Quick Lube", notes = "0W-20 full synthetic",
            origin = DataOrigin.SAMPLE,
        ),
        MaintenanceRecord(
            id = "$vehicleId-tires", vehicleId = vehicleId, date = LocalDate.of(2025, 5, 2),
            title = "Tire rotation", odometerMiles = 58_400,
            origin = DataOrigin.SAMPLE,
        ),
        MaintenanceRecord(
            id = "$vehicleId-brakes", vehicleId = vehicleId, date = LocalDate.of(2024, 11, 20),
            title = "Front brake pads replaced", odometerMiles = 52_100,
            performedBy = "Sample Auto Shop",
            origin = DataOrigin.SAMPLE,
        ),
    )
}
