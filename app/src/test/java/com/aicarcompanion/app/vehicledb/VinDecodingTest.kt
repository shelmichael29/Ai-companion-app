package com.aicarcompanion.app.vehicledb

import com.aicarcompanion.app.data.vehicledb.HttpResponse
import com.aicarcompanion.app.data.vehicledb.LookupError
import com.aicarcompanion.app.data.vehicledb.NhtsaVinDecoder
import com.aicarcompanion.app.data.vehicledb.VinDecodeResult
import com.aicarcompanion.app.data.vehicledb.VinValidator
import com.aicarcompanion.app.data.vehicledb.VpicParser
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.FuelType
import com.aicarcompanion.app.testutil.Fixtures
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * vPIC fixtures were captured from the live API. The BMW VIN uses a synthetic serial number
 * (with a correct check digit) that matches the 2011 528i VIN pattern; it is not a real car.
 */
class VinDecodingTest {

    private val bmwVin = "WBAFR1C56BC000001"

    @Test fun `validator normalizes and checks format`() {
        assertEquals(VinValidator.Result.Valid(bmwVin, checkDigitValid = true), VinValidator.validate("wbafr1c5-6bc000001 "))
        assertTrue(VinValidator.validate("WBAFR1C56BC00000") is VinValidator.Result.Invalid)
        assertTrue(VinValidator.validate("WBAFR1C56BCO00001") is VinValidator.Result.Invalid) // letter O
    }

    @Test fun `wrong check digit is a warning, not invalid`() {
        val r = VinValidator.validate("WBAFR1C50BC000001") as VinValidator.Result.Valid
        assertFalse(r.checkDigitValid)
        assertEquals('6', VinValidator.checkDigit("WBAFR1C50BC000001"))
    }

    @Test fun `2011 BMW 528i decodes basics and reports what is missing`() {
        val r = VpicParser.parse(bmwVin, Fixtures.read("vpic_bmw_528i_2011.json")) as VinDecodeResult.Decoded
        val v = r.vehicle
        assertEquals(2011, v.year)
        assertEquals("BMW", v.make)
        assertEquals("528i", v.model)
        assertEquals("5-Series", v.series)
        assertEquals(3.0, v.displacementLiters!!, 0.0)
        assertEquals(6, v.cylinders)
        assertEquals(FuelType.GASOLINE, v.fuelType)
        // NHTSA does not report these for this vehicle; we must not guess.
        assertEquals(EngineLayout.UNKNOWN, v.layout)
        assertNull(v.engineCode)
        assertNull(v.transmission)
        assertNull(v.trim)
        assertNull(v.aspiration)
        assertTrue("engine layout (inline / V)" in v.notDetermined)
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun `decode with configuration populated`() {
        val r = VpicParser.parse("2HGFC2F52JH000001", Fixtures.read("vpic_honda_civic_2018.json")) as VinDecodeResult.Decoded
        assertEquals("Honda", r.vehicle.make) // tidied from "HONDA"
        assertEquals(EngineLayout.INLINE, r.vehicle.layout)
        assertEquals("K20C2", r.vehicle.engineCode)
        assertEquals("LX", r.vehicle.trim)
        assertEquals("Continuously Variable Transmission (CVT)", r.vehicle.transmission)
    }

    @Test fun `partial decode keeps known fields and warns`() {
        val r = VpicParser.parse("WBAXG5C51BD000001", Fixtures.read("vpic_partial_error.json")) as VinDecodeResult.Decoded
        assertEquals(2011, r.vehicle.year)
        assertNull(r.vehicle.model)
        assertTrue(r.warnings.any { it.contains("mistyped") })
    }

    @Test fun `invalid VIN response is rejected despite misleading fields`() {
        val r = VpicParser.parse("12345678901234567", Fixtures.read("vpic_invalid.json"))
        assertTrue(r is VinDecodeResult.NotDecoded)
    }

    @Test fun `decoder validates locally before calling network`() = runTest {
        var called = false
        val decoder = NhtsaVinDecoder { _, _ -> called = true; HttpResponse(200, "") }
        assertTrue(decoder.decode("TOO-SHORT") is VinDecodeResult.NotDecoded)
        assertFalse(called)
    }

    @Test fun `decoder reports offline`() = runTest {
        val decoder = NhtsaVinDecoder { _, _ -> throw IOException("no network") }
        assertEquals(VinDecodeResult.Failed(LookupError.OFFLINE), decoder.decode(bmwVin))
    }

    @Test fun `decoder parses network response`() = runTest {
        val decoder = NhtsaVinDecoder { url, _ ->
            assertTrue(url.contains(bmwVin))
            HttpResponse(200, Fixtures.read("vpic_bmw_528i_2011.json"))
        }
        assertTrue(decoder.decode(bmwVin) is VinDecodeResult.Decoded)
    }
}
