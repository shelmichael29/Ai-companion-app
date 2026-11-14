package com.aicarcompanion.app.data

import com.aicarcompanion.app.data.memory.InMemoryVehicleRepository
import com.aicarcompanion.app.data.sample.SampleData
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.Vehicle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryVehicleRepositoryTest {

    private fun vehicle(id: String) = Vehicle(id, 2020, "Make", "Model", engine = EngineSpec())

    @Test fun `first added vehicle becomes active`() = runTest {
        val repo = InMemoryVehicleRepository()
        assertNull(repo.activeVehicleId.first())
        repo.upsert(vehicle("a"))
        assertEquals("a", repo.activeVehicleId.first())
    }

    @Test fun `upsert replaces existing vehicle`() = runTest {
        val repo = InMemoryVehicleRepository(listOf(vehicle("a")))
        repo.upsert(vehicle("a").copy(model = "Changed"))
        assertEquals(listOf("Changed"), repo.vehicles.first().map { it.model })
    }

    @Test fun `deleting active vehicle selects another`() = runTest {
        val repo = InMemoryVehicleRepository(listOf(vehicle("a"), vehicle("b")))
        repo.delete("a")
        assertEquals("b", repo.activeVehicleId.first())
        repo.delete("b")
        assertNull(repo.activeVehicleId.first())
    }

    @Test fun `setActive ignores unknown ids`() = runTest {
        val repo = InMemoryVehicleRepository(listOf(vehicle("a")))
        repo.setActive("missing")
        assertEquals("a", repo.activeVehicleId.first())
    }

    @Test fun `all sample diagnostics are labeled as sample data`() {
        assertTrue(SampleData.troubleCodes().all { it.origin == DataOrigin.SAMPLE })
        assertTrue(SampleData.sensorReadings().all { it.origin == DataOrigin.SAMPLE })
        assertTrue(SampleData.maintenance("x").all { it.origin == DataOrigin.SAMPLE })
        assertTrue(SampleData.troubleCodes().isNotEmpty())
    }
}
