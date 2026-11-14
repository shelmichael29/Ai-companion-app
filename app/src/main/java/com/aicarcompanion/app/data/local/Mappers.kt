package com.aicarcompanion.app.data.local

import com.aicarcompanion.app.domain.model.Aspiration
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DiagnosticObservation
import com.aicarcompanion.app.domain.model.DtcStatus
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.FuelType
import com.aicarcompanion.app.domain.model.IdentificationSource
import com.aicarcompanion.app.domain.model.MaintenanceRecord
import com.aicarcompanion.app.domain.model.MileageEntry
import com.aicarcompanion.app.domain.model.ObservationKind
import com.aicarcompanion.app.domain.model.RecordedSensorReading
import com.aicarcompanion.app.domain.model.RecordedTroubleCode
import com.aicarcompanion.app.domain.model.ServiceKind
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.domain.model.VehicleIdentification
import java.time.Instant
import java.time.LocalDate

/** Enum lookup that tolerates values written by a newer/older app version. */
internal inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: fallback

fun Vehicle.toEntity(nowMillis: Long) = VehicleEntity(
    id = id, year = year, make = make, model = model, trim = trim,
    displacementLiters = engine.displacementLiters, cylinders = engine.cylinders,
    engineLayout = engine.layout.name, fuelType = engine.fuelType.name, aspiration = engine.aspiration.name,
    engineCode = engine.engineCode, fuelGrade = engine.fuelGrade,
    transmission = transmission, drivetrain = drivetrain, vin = vin, odometerMiles = odometerMiles,
    nickname = nickname, identificationSource = identification.source.name,
    catalogId = identification.catalogId, userConfirmed = identification.userConfirmed,
    updatedAtMillis = nowMillis,
)

fun VehicleEntity.toDomain() = Vehicle(
    id = id, year = year, make = make, model = model, trim = trim,
    engine = EngineSpec(
        displacementLiters = displacementLiters,
        cylinders = cylinders,
        layout = enumOr(engineLayout, EngineLayout.UNKNOWN),
        fuelType = enumOr(fuelType, FuelType.GASOLINE),
        aspiration = enumOr(aspiration, Aspiration.NATURALLY_ASPIRATED),
        engineCode = engineCode,
        fuelGrade = fuelGrade,
    ),
    transmission = transmission, drivetrain = drivetrain, vin = vin, odometerMiles = odometerMiles,
    nickname = nickname,
    identification = VehicleIdentification(
        enumOr(identificationSource, IdentificationSource.MANUAL), catalogId, userConfirmed,
    ),
)

fun MaintenanceRecord.toEntity() = ServiceRecordEntity(
    id = id, vehicleId = vehicleId, kind = kind.name, dateEpochDay = date.toEpochDay(), title = title,
    odometerMiles = odometerMiles, performedBy = performedBy, costCents = costCents, notes = notes,
    origin = origin.name,
)

fun ServiceRecordEntity.toDomain() = MaintenanceRecord(
    id = id, vehicleId = vehicleId, date = LocalDate.ofEpochDay(dateEpochDay), title = title,
    kind = enumOr(kind, ServiceKind.MAINTENANCE), odometerMiles = odometerMiles, performedBy = performedBy,
    costCents = costCents, notes = notes, origin = enumOr(origin, DataOrigin.USER_ENTERED),
)

fun TroubleCodeEntity.toDomain() = RecordedTroubleCode(
    id = id, vehicleId = vehicleId, code = code, status = enumOr(status, DtcStatus.STORED),
    readAt = Instant.ofEpochMilli(readAtMillis), readSessionId = readSessionId,
    origin = enumOr(origin, DataOrigin.USER_ENTERED),
)

fun RecordedSensorReading.toEntity() = SensorReadingEntity(
    id = 0, vehicleId = vehicleId, pid = pid, name = name, value = value, unit = unit,
    recordedAtMillis = recordedAt.toEpochMilli(), origin = origin.name,
)

fun SensorReadingEntity.toDomain() = RecordedSensorReading(
    id = id, vehicleId = vehicleId, pid = pid, name = name, value = value, unit = unit,
    recordedAt = Instant.ofEpochMilli(recordedAtMillis), origin = enumOr(origin, DataOrigin.USER_ENTERED),
)

fun DiagnosticObservation.toEntity() = ObservationEntity(
    id = id, vehicleId = vehicleId, createdAtMillis = createdAt.toEpochMilli(), kind = kind.name,
    text = text, relatedCode = relatedCode, origin = origin.name,
)

fun ObservationEntity.toDomain() = DiagnosticObservation(
    id = id, vehicleId = vehicleId, createdAt = Instant.ofEpochMilli(createdAtMillis),
    // Unknown kinds fall back to NOTE (unverified), never to VERIFIED_FINDING.
    kind = enumOr(kind, ObservationKind.NOTE), text = text, relatedCode = relatedCode,
    origin = enumOr(origin, DataOrigin.USER_ENTERED),
)

fun MileageEntity.toDomain() = MileageEntry(
    id = id, vehicleId = vehicleId, odometerMiles = odometerMiles,
    recordedAt = Instant.ofEpochMilli(recordedAtMillis), origin = enumOr(origin, DataOrigin.USER_ENTERED),
)
