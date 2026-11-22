package com.aicarcompanion.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "vehicles")
data class VehicleEntity(
    @PrimaryKey val id: String,
    val year: Int,
    val make: String,
    val model: String,
    val trim: String?,
    val displacementLiters: Double?,
    val cylinders: Int?,
    val engineLayout: String,
    val fuelType: String,
    val aspiration: String,
    val engineCode: String?,
    val fuelGrade: String?,
    val transmission: String?,
    val drivetrain: String?,
    val vin: String?,
    val odometerMiles: Int?,
    val nickname: String?,
    val identificationSource: String,
    val catalogId: String?,
    val userConfirmed: Boolean,
    val updatedAtMillis: Long,
)

private const val VEHICLE_FK = "vehicleId"

@Entity(
    tableName = "service_records",
    foreignKeys = [ForeignKey(VehicleEntity::class, ["id"], [VEHICLE_FK], onDelete = ForeignKey.CASCADE)],
    indices = [Index(VEHICLE_FK)],
)
data class ServiceRecordEntity(
    @PrimaryKey val id: String,
    val vehicleId: String,
    val kind: String,
    val dateEpochDay: Long,
    val title: String,
    val odometerMiles: Int?,
    val performedBy: String?,
    val costCents: Long?,
    val notes: String?,
    val origin: String,
)

@Entity(
    tableName = "dtc_reads",
    foreignKeys = [ForeignKey(VehicleEntity::class, ["id"], [VEHICLE_FK], onDelete = ForeignKey.CASCADE)],
    indices = [Index(VEHICLE_FK)],
)
data class TroubleCodeReadEntity(
    @PrimaryKey val sessionId: String,
    val vehicleId: String,
    val readAtMillis: Long,
    val origin: String,
)

@Entity(
    tableName = "dtc_records",
    foreignKeys = [
        ForeignKey(VehicleEntity::class, ["id"], [VEHICLE_FK], onDelete = ForeignKey.CASCADE),
        ForeignKey(TroubleCodeReadEntity::class, ["sessionId"], ["readSessionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(VEHICLE_FK), Index("readSessionId")],
)
data class TroubleCodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,
    val readSessionId: String,
    val code: String,
    val status: String,
    val readAtMillis: Long,
    val origin: String,
)

@Entity(
    tableName = "sensor_readings",
    foreignKeys = [ForeignKey(VehicleEntity::class, ["id"], [VEHICLE_FK], onDelete = ForeignKey.CASCADE)],
    indices = [Index(VEHICLE_FK), Index("recordedAtMillis")],
)
data class SensorReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,
    val pid: String?,
    val name: String,
    val value: Double,
    val unit: String,
    val recordedAtMillis: Long,
    val origin: String,
)

@Entity(
    tableName = "observations",
    foreignKeys = [ForeignKey(VehicleEntity::class, ["id"], [VEHICLE_FK], onDelete = ForeignKey.CASCADE)],
    indices = [Index(VEHICLE_FK)],
)
data class ObservationEntity(
    @PrimaryKey val id: String,
    val vehicleId: String,
    val createdAtMillis: Long,
    val kind: String,
    val text: String,
    val relatedCode: String?,
    val origin: String,
)

@Entity(
    tableName = "mileage",
    foreignKeys = [ForeignKey(VehicleEntity::class, ["id"], [VEHICLE_FK], onDelete = ForeignKey.CASCADE)],
    indices = [Index(VEHICLE_FK)],
)
data class MileageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,
    val odometerMiles: Int,
    val recordedAtMillis: Long,
    val origin: String,
)

/** Raw responses from vehicle-information services, for offline reuse. */
@Entity(tableName = "response_cache")
data class CachedResponseEntity(
    @PrimaryKey val key: String,
    val body: String,
    val fetchedAtMillis: Long,
)

// ---- Live Data dashboards (database version 2) ------------------------------------------------

@Entity(
    tableName = "dashboard_layouts",
    foreignKeys = [ForeignKey(VehicleEntity::class, ["id"], [VEHICLE_FK], onDelete = ForeignKey.CASCADE)],
    indices = [Index(VEHICLE_FK)],
)
data class DashboardLayoutEntity(
    @PrimaryKey val id: String,
    val vehicleId: String,
    val name: String,
    val position: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/** One gauge. [slot] is 0..7 (at most 8 per layout) and is the gauge's place in the layout. */
@Entity(
    tableName = "dashboard_gauges",
    primaryKeys = ["layoutId", "slot"],
    foreignKeys = [ForeignKey(DashboardLayoutEntity::class, ["id"], ["layoutId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["layoutId", "pid"], unique = true)],
)
data class DashboardGaugeEntity(
    val layoutId: String,
    val slot: Int,
    val pid: Int,
    val style: String,
    val size: String,
    val unit: String?,
)

/** Which layout a vehicle last used. */
@Entity(
    tableName = "dashboard_prefs",
    foreignKeys = [ForeignKey(VehicleEntity::class, ["id"], ["vehicleId"], onDelete = ForeignKey.CASCADE)],
)
data class DashboardPrefsEntity(
    @PrimaryKey val vehicleId: String,
    val activeLayoutId: String?,
)
