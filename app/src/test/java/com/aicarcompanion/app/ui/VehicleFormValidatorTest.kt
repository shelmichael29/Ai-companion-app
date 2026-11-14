package com.aicarcompanion.app.ui

import com.aicarcompanion.app.domain.model.Aspiration
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.FuelType
import com.aicarcompanion.app.ui.vehicles.VehicleField
import com.aicarcompanion.app.ui.vehicles.VehicleForm
import com.aicarcompanion.app.ui.vehicles.VehicleFormResult
import com.aicarcompanion.app.ui.vehicles.VehicleFormValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleFormValidatorTest {

    private val validForm = VehicleForm(
        year = "2018", make = " Honda ", model = "Civic", trim = "EX",
        displacementLiters = "2.0", cylinders = "4", vin = "2hgfc2f59jh000000", odometerMiles = "64,210",
    )

    private fun validate(form: VehicleForm) = VehicleFormValidator.validate(form, "id", currentYear = 2026)

    @Test fun `valid form produces trimmed vehicle`() {
        val vehicle = (validate(validForm) as VehicleFormResult.Valid).vehicle
        assertEquals("Honda", vehicle.make)
        assertEquals("2018 Honda Civic EX", vehicle.displayName)
        // Layout isn't known from the form, so it must not be guessed.
        assertEquals("2.0L 4-cyl Gasoline", vehicle.engine.description)
        assertEquals("2HGFC2F59JH000000", vehicle.vin)
        assertEquals(64_210, vehicle.odometerMiles)
    }

    @Test fun `required fields are reported`() {
        val errors = (validate(VehicleForm()) as VehicleFormResult.Invalid).errors
        assertTrue(errors.keys.containsAll(listOf(VehicleField.YEAR, VehicleField.MAKE, VehicleField.MODEL)))
    }

    @Test fun `rejects bad year, vin, and engine values`() {
        val errors = (validate(
            validForm.copy(year = "2031", vin = "IOQ", displacementLiters = "abc", cylinders = "40"),
        ) as VehicleFormResult.Invalid).errors
        assertEquals(
            setOf(VehicleField.YEAR, VehicleField.VIN, VehicleField.DISPLACEMENT, VehicleField.CYLINDERS),
            errors.keys,
        )
    }

    @Test fun `electric vehicles ignore combustion engine fields`() {
        val vehicle = (validate(
            validForm.copy(fuelType = FuelType.ELECTRIC, displacementLiters = "junk", aspiration = Aspiration.TURBOCHARGED),
        ) as VehicleFormResult.Valid).vehicle
        assertNull(vehicle.engine.displacementLiters)
        assertEquals("Electric", vehicle.engine.description)
    }

    @Test fun `2011 BMW 528i N52 is described as an inline six, never a V6`() {
        val vehicle = (validate(
            VehicleForm(
                year = "2011", make = "BMW", model = "528i", trim = "F10", displacementLiters = "3.0",
                cylinders = "6", layout = EngineLayout.INLINE, engineCode = "N52B30",
            ),
        ) as VehicleFormResult.Valid).vehicle
        assertEquals("3.0L I6 Gasoline", vehicle.engine.description)
        assertEquals("3.0 liter naturally aspirated inline six-cylinder", vehicle.engine.spokenDescription)
        assertEquals("N52B30", vehicle.engine.engineCode)
    }

    @Test fun `six cylinders with unknown layout is not called a V6`() {
        val vehicle = (validate(VehicleForm(year = "2011", make = "BMW", model = "528i", displacementLiters = "3.0", cylinders = "6"))
            as VehicleFormResult.Valid).vehicle
        assertEquals("3.0L 6-cyl Gasoline", vehicle.engine.description)
    }

    @Test fun `optional fields may be blank`() {
        val result = validate(VehicleForm(year = "2020", make = "Tesla", model = "Model 3"))
        assertTrue(result is VehicleFormResult.Valid)
    }
}
