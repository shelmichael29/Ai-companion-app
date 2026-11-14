package com.aicarcompanion.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        VehicleEntity::class,
        ServiceRecordEntity::class,
        TroubleCodeReadEntity::class,
        TroubleCodeEntity::class,
        SensorReadingEntity::class,
        ObservationEntity::class,
        MileageEntity::class,
        CachedResponseEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vehicleDao(): VehicleDao
    abstract fun serviceRecordDao(): ServiceRecordDao
    abstract fun diagnosticHistoryDao(): DiagnosticHistoryDao
    abstract fun responseCacheDao(): ResponseCacheDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "car_companion.db").build()
    }
}
