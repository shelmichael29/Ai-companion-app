package com.aicarcompanion.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface VehicleDao {
    @Query("SELECT * FROM vehicles ORDER BY year DESC, make, model")
    fun observeAll(): Flow<List<VehicleEntity>>

    @Query("SELECT * FROM vehicles WHERE id = :id")
    fun observe(id: String): Flow<VehicleEntity?>

    @Upsert
    suspend fun upsert(vehicle: VehicleEntity)

    @Query("DELETE FROM vehicles WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT id FROM vehicles ORDER BY year DESC, make, model LIMIT 1")
    suspend fun firstId(): String?
}

@Dao
interface ServiceRecordDao {
    @Query("SELECT * FROM service_records WHERE vehicleId = :vehicleId ORDER BY dateEpochDay DESC")
    fun observe(vehicleId: String): Flow<List<ServiceRecordEntity>>

    @Upsert
    suspend fun upsert(record: ServiceRecordEntity)

    @Query("DELETE FROM service_records WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface DiagnosticHistoryDao {
    @Query("SELECT * FROM dtc_reads WHERE vehicleId = :vehicleId ORDER BY readAtMillis DESC LIMIT 1")
    fun observeLatestRead(vehicleId: String): Flow<TroubleCodeReadEntity?>

    @Query("SELECT * FROM dtc_records WHERE readSessionId = :sessionId ORDER BY code")
    fun observeCodesForRead(sessionId: String): Flow<List<TroubleCodeEntity>>

    @Query("SELECT * FROM dtc_records WHERE vehicleId = :vehicleId ORDER BY readAtMillis DESC, code")
    fun observeCodeHistory(vehicleId: String): Flow<List<TroubleCodeEntity>>

    @Insert
    suspend fun insertRead(read: TroubleCodeReadEntity)

    @Insert
    suspend fun insertCodes(codes: List<TroubleCodeEntity>)

    @Transaction
    suspend fun insertReadWithCodes(read: TroubleCodeReadEntity, codes: List<TroubleCodeEntity>) {
        insertRead(read)
        if (codes.isNotEmpty()) insertCodes(codes)
    }

    @Query("SELECT * FROM sensor_readings WHERE vehicleId = :vehicleId ORDER BY recordedAtMillis DESC LIMIT :limit")
    fun observeSensorReadings(vehicleId: String, limit: Int): Flow<List<SensorReadingEntity>>

    @Insert
    suspend fun insertSensorReadings(readings: List<SensorReadingEntity>)

    @Query("SELECT * FROM observations WHERE vehicleId = :vehicleId ORDER BY createdAtMillis DESC")
    fun observeObservations(vehicleId: String): Flow<List<ObservationEntity>>

    @Upsert
    suspend fun upsertObservation(observation: ObservationEntity)

    @Query("DELETE FROM observations WHERE id = :id")
    suspend fun deleteObservation(id: String)

    @Query("SELECT * FROM mileage WHERE vehicleId = :vehicleId ORDER BY recordedAtMillis DESC")
    fun observeMileage(vehicleId: String): Flow<List<MileageEntity>>

    @Insert
    suspend fun insertMileage(entry: MileageEntity)
}

@Dao
interface ResponseCacheDao {
    @Query("SELECT * FROM response_cache WHERE `key` = :key")
    suspend fun get(key: String): CachedResponseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: CachedResponseEntity)
}
