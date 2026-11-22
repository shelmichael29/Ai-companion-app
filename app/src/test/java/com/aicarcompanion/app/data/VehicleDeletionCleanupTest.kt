package com.aicarcompanion.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aicarcompanion.app.data.local.AppDatabase
import com.aicarcompanion.app.data.local.InMemorySettingsStore
import com.aicarcompanion.app.data.local.RoomVehicleRepository
import com.aicarcompanion.app.data.photos.VehiclePhotoStore
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.Vehicle
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class VehicleDeletionCleanupTest {
    @Test fun `deleting a vehicle also deletes its local photo`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val photoStore = VehiclePhotoStore(context)
            val repo = RoomVehicleRepository(
                database.vehicleDao(),
                InMemorySettingsStore(),
                onVehicleDeleted = photoStore::delete,
            )
            val vehicle = Vehicle("photo-test-vehicle", 2011, "BMW", "528i", engine = EngineSpec())
            repo.upsert(vehicle)
            val photo = File(File(context.filesDir, "vehicle_photos"), "photo-test-vehicle.jpg")
            photo.parentFile!!.mkdirs()
            photo.writeText("placeholder photo bytes")
            assertTrue(photo.exists())
            repo.delete(vehicle.id)
            assertFalse("The deleted vehicle's photo must not linger", photo.exists())
        } finally {
            database.close()
        }
    }
}
