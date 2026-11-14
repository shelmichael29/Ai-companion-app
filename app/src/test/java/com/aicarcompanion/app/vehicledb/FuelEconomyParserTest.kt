package com.aicarcompanion.app.vehicledb

import com.aicarcompanion.app.data.vehicledb.FuelEconomyParser
import com.aicarcompanion.app.data.vehicledb.TransmissionText
import com.aicarcompanion.app.domain.model.Aspiration
import com.aicarcompanion.app.domain.model.FuelType
import com.aicarcompanion.app.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses responses captured from the live FuelEconomy.gov API. */
class FuelEconomyParserTest {

    @Test fun `single result menu is an object, not an array`() {
        val items = FuelEconomyParser.parseMenu(Fixtures.read("fueleconomy_options_single.json"))
        assertEquals(1, items.size)
        assertEquals("Auto (S8), 6 cyl, 3.0 L", items[0].text)
        assertEquals("30070", items[0].value)
    }

    @Test fun `null body means no results`() {
        assertTrue(FuelEconomyParser.parseMenu(Fixtures.read("fueleconomy_empty.json")).isEmpty())
        assertTrue(FuelEconomyParser.parseMenu("").isEmpty())
    }

    @Test fun `multi result menu`() {
        val makes = FuelEconomyParser.parseMenu(Fixtures.read("fueleconomy_makes_2011.json")).map { it.value }
        assertTrue("BMW" in makes)
        assertTrue(makes.size > 20)
    }

    @Test fun `parses 2011 BMW 528i configuration without inventing a layout`() {
        val item = FuelEconomyParser.parseMenu(Fixtures.read("fueleconomy_options_single.json")).single()
        val c = FuelEconomyParser.parseConfiguration(item)
        assertEquals("Auto (S8)", c.transmission)
        assertEquals(6, c.cylinders)
        assertEquals(3.0, c.displacementLiters!!, 0.0)
        assertEquals(emptyList<String>(), c.extras)
        assertEquals("3.0L 6-cyl", c.engineLabel) // not "V6"
    }

    @Test fun `parses extras like diesel and turbo`() {
        val configs = FuelEconomyParser.parseMenu(Fixtures.read("fueleconomy_options_multi.json"))
            .map(FuelEconomyParser::parseConfiguration)
        val diesel = configs.first { it.id == "35352" }
        assertEquals(listOf("Diesel", "Turbo"), diesel.extras)
        assertEquals("2.0L 4-cyl Diesel Turbo", diesel.engineLabel)
        assertEquals("Auto (AM-S6)", diesel.transmission)
    }

    @Test fun `electric vehicle option has no engine`() {
        val c = FuelEconomyParser.parseConfiguration(FuelEconomyParser.MenuItem("Auto (A1)", "46204"))
        assertNull(c.cylinders)
        assertNull(c.displacementLiters)
        assertTrue(c.engineLabel.startsWith("No engine listed"))
    }

    @Test fun `parses 2011 528i vehicle details`() {
        val specs = FuelEconomyParser.parseSpecs(Fixtures.read("fueleconomy_vehicle_30070.json"))
        assertEquals(2011, specs.year)
        assertEquals("BMW", specs.make)
        assertEquals("528i", specs.model)
        assertEquals("5 Series", specs.baseModel)
        assertEquals(6, specs.cylinders)
        assertEquals(3.0, specs.displacementLiters!!, 0.0)
        assertEquals("Automatic (S8)", specs.transmission)
        assertEquals("Rear-Wheel Drive", specs.drivetrain)
        assertEquals(FuelType.GASOLINE, specs.fuelType)
        assertEquals("Premium", specs.fuelGrade)
        assertEquals(Aspiration.NATURALLY_ASPIRATED, specs.aspiration)
    }

    @Test fun `transmission descriptions`() {
        assertEquals("Automatic, 8-speed, with manual shift mode", TransmissionText.describe("Auto (S8)"))
        assertEquals("Automatic, 8-speed, with manual shift mode", TransmissionText.describe("Automatic (S8)"))
        assertEquals("Manual, 6-speed", TransmissionText.describe("Man 6-spd"))
        assertEquals("Automated manual, 6-speed", TransmissionText.describe("Auto (AM-S6)"))
        assertEquals("Automatic (CVT with 7 simulated steps)", TransmissionText.describe("Auto (AV-S7)"))
        assertEquals("Automatic (CVT)", TransmissionText.describe("Auto (variable gear ratios)"))
        assertEquals("Automatic, single-speed", TransmissionText.describe("Auto (A1)"))
    }
}
