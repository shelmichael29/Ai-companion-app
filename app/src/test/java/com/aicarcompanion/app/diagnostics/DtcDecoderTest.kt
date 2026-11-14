package com.aicarcompanion.app.diagnostics

import com.aicarcompanion.app.diagnostics.dtc.CodeScope
import com.aicarcompanion.app.diagnostics.dtc.DtcCodeFormat
import com.aicarcompanion.app.diagnostics.dtc.DtcDecoder
import com.aicarcompanion.app.diagnostics.dtc.GenericDtcCatalog
import com.aicarcompanion.app.diagnostics.dtc.VehicleSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcDecoderTest {

    @Test fun `decodes each system letter`() {
        assertEquals("P0133", DtcDecoder.decode(0x01, 0x33))
        assertEquals("C0035", DtcDecoder.decode(0x40, 0x35))
        assertEquals("B1234", DtcDecoder.decode(0x92, 0x34))
        assertEquals("U0100", DtcDecoder.decode(0xC1, 0x00))
    }

    @Test fun `decodes hex digits in third position`() {
        assertEquals("P2A00", DtcDecoder.decode(0x2A, 0x00))
    }

    @Test fun `padding bytes produce no code`() {
        assertNull(DtcDecoder.decode(0x00, 0x00))
        assertEquals(listOf("P0420"), DtcDecoder.decodePayload(intArrayOf(0x04, 0x20, 0x00, 0x00, 0x00, 0x00)))
    }

    @Test fun `code format helpers`() {
        assertTrue(DtcCodeFormat.isValid("p0420"))
        assertFalse(DtcCodeFormat.isValid("P4420"))
        assertFalse(DtcCodeFormat.isValid("X0420"))
        assertEquals(VehicleSystem.NETWORK, DtcCodeFormat.system("U0100"))
        assertEquals(CodeScope.GENERIC, DtcCodeFormat.scope("P0420"))
        assertEquals(CodeScope.GENERIC, DtcCodeFormat.scope("P2096"))
        assertEquals(CodeScope.MANUFACTURER_SPECIFIC, DtcCodeFormat.scope("P1456"))
        assertEquals(CodeScope.MANUFACTURER_SPECIFIC, DtcCodeFormat.scope("B2000"))
    }

    @Test fun `catalog lookup is case-insensitive and returns null for unknown codes`() {
        assertNotNull(GenericDtcCatalog.lookup("p0420"))
        assertNull(GenericDtcCatalog.lookup("P1999"))
    }
}
