package com.aicarcompanion.app.ui.history

import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DiagnosticObservation
import com.aicarcompanion.app.domain.model.DtcStatus
import com.aicarcompanion.app.domain.model.MaintenanceRecord
import com.aicarcompanion.app.domain.model.ObservationKind
import com.aicarcompanion.app.domain.model.ServiceKind
import com.aicarcompanion.app.diagnostics.dtc.DtcCodeFormat
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Editable service/repair entry. Pure so validation is unit-testable. */
data class ServiceForm(
    val kind: ServiceKind = ServiceKind.MAINTENANCE,
    val title: String = "",
    val date: String = LocalDate.now().toString(),
    val odometer: String = "",
    val performedBy: String = "",
    val cost: String = "",
    val notes: String = "",
) {
    fun validate(id: String, vehicleId: String, today: LocalDate): Result<MaintenanceRecord> {
        val errors = mutableListOf<String>()
        if (title.isBlank()) errors += "Describe the work, e.g. \"Oil and filter change\"."
        val parsedDate = try { LocalDate.parse(date.trim()) } catch (e: DateTimeParseException) { null }
        if (parsedDate == null) errors += "Enter the date as YYYY-MM-DD."
        else if (parsedDate.isAfter(today)) errors += "The date can't be in the future."
        val miles = odometer.replace(",", "").trim().takeIf { it.isNotEmpty() }?.let {
            it.toIntOrNull()?.takeIf { m -> m in 0..2_000_000 } ?: null.also { errors += "Odometer must be whole miles." }
        }
        val cents = cost.replace("$", "").replace(",", "").trim().takeIf { it.isNotEmpty() }?.let {
            runCatching { BigDecimal(it).movePointRight(2).longValueExact() }.getOrNull()?.takeIf { c -> c >= 0 }
                ?: null.also { errors += "Cost must be an amount like 89.99." }
        }
        if (errors.isNotEmpty()) return Result.failure(IllegalArgumentException(errors.joinToString(" ")))
        return Result.success(
            MaintenanceRecord(
                id = id, vehicleId = vehicleId, date = parsedDate!!, title = title.trim(), kind = kind,
                odometerMiles = miles, performedBy = performedBy.trim().ifEmpty { null }, costCents = cents,
                notes = notes.trim().ifEmpty { null }, origin = DataOrigin.USER_ENTERED,
            ),
        )
    }
}

data class ObservationForm(
    val kind: ObservationKind = ObservationKind.SYMPTOM,
    val text: String = "",
    val relatedCode: String = "",
) {
    fun validate(id: String, vehicleId: String, now: Instant): Result<DiagnosticObservation> {
        if (text.isBlank()) return Result.failure(IllegalArgumentException("Write what you noticed or found."))
        val code = relatedCode.trim().uppercase().ifEmpty { null }
        if (code != null && !DtcCodeFormat.isValid(code)) {
            return Result.failure(IllegalArgumentException("\"$code\" isn't a valid trouble code (e.g. P0420)."))
        }
        return Result.success(
            DiagnosticObservation(id, vehicleId, now, kind, text.trim(), code, DataOrigin.USER_ENTERED),
        )
    }
}

/** Groups a flat code history into reads, newest first. */
data class CodeReadSummary(val readAt: Instant, val origin: DataOrigin, val codes: List<Pair<String, DtcStatus>>)
