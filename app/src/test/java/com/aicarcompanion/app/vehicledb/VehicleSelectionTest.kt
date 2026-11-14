package com.aicarcompanion.app.vehicledb

import com.aicarcompanion.app.data.vehicledb.FuelEconomyCatalogProvider
import com.aicarcompanion.app.data.vehicledb.HttpResponse
import com.aicarcompanion.app.data.vehicledb.InMemoryResponseCache
import com.aicarcompanion.app.data.vehicledb.LookupResult
import com.aicarcompanion.app.testutil.Fixtures
import com.aicarcompanion.app.ui.vehicles.Options
import com.aicarcompanion.app.ui.vehicles.VehicleForm
import com.aicarcompanion.app.ui.vehicles.VehicleSelectionController
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.IdentificationSource
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class VehicleSelectionTest {

    /** Fake FuelEconomy.gov server answering from captured fixtures. */
    private class FakeServer {
        var online = true
        val requests = mutableListOf<String>()
        fun get(url: String): HttpResponse {
            requests += url
            if (!online) throw IOException("offline")
            val body = when {
                url.endsWith("/menu/year") -> """{"menuItem":[{"text":"2012","value":"2012"},{"text":"2011","value":"2011"}]}"""
                url.contains("/menu/make?year=2011") -> Fixtures.read("fueleconomy_makes_2011.json")
                url.contains("/menu/model?year=2011&make=BMW") -> """{"menuItem":[{"text":"528i","value":"528i"},{"text":"535i","value":"535i"}]}"""
                url.contains("/menu/options?year=2011&make=BMW&model=528i") -> Fixtures.read("fueleconomy_options_single.json")
                url.contains("/menu/options?year=2015&make=Volkswagen&model=Golf") -> Fixtures.read("fueleconomy_options_multi.json")
                url.endsWith("/vehicle/30070") -> Fixtures.read("fueleconomy_vehicle_30070.json")
                else -> "null"
            }
            return HttpResponse(200, body)
        }
    }

    private fun TestScope.setup(server: FakeServer = FakeServer(), cache: InMemoryResponseCache = InMemoryResponseCache()) =
        VehicleSelectionController(FuelEconomyCatalogProvider({ url, _ -> server.get(url) }, cache, now = { 0L }), this)

    @Test fun `dependent selection auto-picks single engine and transmission for 2011 528i`() = runTest {
        val c = setup()
        c.loadYears(); advanceUntilIdle()
        assertEquals(listOf(2012, 2011), c.state.value.years.itemsOrEmpty)

        c.selectYear(2011); advanceUntilIdle()
        assertTrue("BMW" in c.state.value.makes.itemsOrEmpty)

        c.selectMake("BMW"); advanceUntilIdle()
        assertEquals(listOf("528i", "535i"), c.state.value.models.itemsOrEmpty)

        c.selectModel("528i"); advanceUntilIdle()
        val s = c.state.value
        assertEquals("3.0L 6-cyl", s.engine)
        assertEquals("30070", s.configuration?.id)
        val specs = (s.specs as Options.Loaded).items.single()
        assertEquals("Rear-Wheel Drive", specs.drivetrain)

        val form = VehicleForm().withCatalogSelection(2011, "BMW", "528i", s.configuration!!, specs)
        assertEquals("3.0", form.displacementLiters)
        assertEquals("6", form.cylinders)
        assertEquals(EngineLayout.UNKNOWN, form.layout) // user must confirm inline-six
        assertEquals("Premium", form.fuelGrade)
        assertEquals(IdentificationSource.CATALOG, form.source)
    }

    @Test fun `changing year clears downstream choices`() = runTest {
        val c = setup()
        c.selectYear(2011); advanceUntilIdle()
        c.selectMake("BMW"); advanceUntilIdle()
        c.selectModel("528i"); advanceUntilIdle()
        c.selectYear(2012); advanceUntilIdle()
        val s = c.state.value
        assertEquals(2012, s.year)
        assertNull(s.make)
        assertNull(s.model)
        assertNull(s.configuration)
    }

    @Test fun `multiple engines require a choice, transmissions filter by engine`() = runTest {
        val c = setup()
        c.selectYear(2015); advanceUntilIdle()
        c.selectMake("Volkswagen"); advanceUntilIdle()
        c.selectModel("Golf"); advanceUntilIdle()
        assertNull(c.state.value.engine)
        assertEquals(2, c.state.value.engines.size)
        c.selectEngine("1.8L 4-cyl Turbo"); advanceUntilIdle()
        assertEquals(listOf("35353", "35349"), c.state.value.transmissions.map { it.id })
        assertNull(c.state.value.configuration)
    }

    @Test fun `offline with empty cache reports error, with cache serves saved list`() = runTest {
        val server = FakeServer()
        val cache = InMemoryResponseCache()
        val provider = FuelEconomyCatalogProvider({ url, _ -> server.get(url) }, cache, now = { 0L })
        server.online = false
        assertTrue(provider.years() is LookupResult.Failure)

        server.online = true
        provider.years()
        server.online = false
        val offline = provider.years() as LookupResult.Success
        assertTrue(offline.fromCache)
        assertEquals(listOf(2012, 2011), offline.value)
    }

    @Test fun `fresh cache avoids network, stale cache refreshes`() = runTest {
        val server = FakeServer()
        val cache = InMemoryResponseCache()
        var now = 0L
        val provider = FuelEconomyCatalogProvider({ url, _ -> server.get(url) }, cache, now = { now })
        provider.years(); provider.years()
        assertEquals(1, server.requests.size)
        now += 31L * 24 * 60 * 60 * 1000
        provider.years()
        assertEquals(2, server.requests.size)
    }

    @Test fun `make and model are URL encoded`() = runTest {
        val server = FakeServer()
        val provider = FuelEconomyCatalogProvider({ url, _ -> server.get(url) }, InMemoryResponseCache(), now = { 0L })
        provider.models(2011, "Aston Martin")
        assertTrue(server.requests.last().endsWith("make=Aston%20Martin"))
    }
}
