package com.aicarcompanion.app.diagnostics

import com.aicarcompanion.app.diagnostics.obd.Elm327ResponseParser
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import com.aicarcompanion.app.diagnostics.obd.ObdResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Elm327ResponseParserTest {

    @Test fun `decodes engine rpm`() {
        assertEquals(1726.0, Elm327ResponseParser.decodePid("41 0C 1A F8\r\r>", ObdPid.ENGINE_RPM)!!, 0.001)
    }

    @Test fun `decodes coolant temperature with SEARCHING prefix`() {
        assertEquals(50.0, Elm327ResponseParser.decodePid("SEARCHING...\r41 05 5A", ObdPid.COOLANT_TEMP)!!, 0.001)
    }

    @Test fun `decodes control module voltage`() {
        assertEquals(14.1, Elm327ResponseParser.decodePid("41 42 37 14", ObdPid.CONTROL_MODULE_VOLTAGE)!!, 0.001)
    }

    @Test fun `mismatched pid returns null`() {
        assertNull(Elm327ResponseParser.decodePid("41 0D 32", ObdPid.ENGINE_RPM))
    }

    @Test fun `no data and errors are reported, not turned into values`() {
        assertEquals(ObdResponse.NoData, Elm327ResponseParser.parse("NO DATA\r>"))
        assertTrue(Elm327ResponseParser.parse("UNABLE TO CONNECT") is ObdResponse.Error)
        assertTrue(Elm327ResponseParser.parse("?") is ObdResponse.Error)
        assertNull(Elm327ResponseParser.decodePid("NO DATA", ObdPid.ENGINE_RPM))
    }

    @Test fun `decodes trouble codes without count byte`() {
        assertEquals(
            listOf("P0133", "P0420"),
            Elm327ResponseParser.decodeTroubleCodes("43 01 33 04 20 00 00"),
        )
    }

    @Test fun `decodes CAN trouble codes with count byte`() {
        assertEquals(listOf("P0300", "P0171"), Elm327ResponseParser.decodeTroubleCodes("43 02 03 00 01 71"))
    }

    @Test fun `no stored codes yields empty list`() {
        assertEquals(emptyList<String>(), Elm327ResponseParser.decodeTroubleCodes("43 00"))
        assertEquals(emptyList<String>(), Elm327ResponseParser.decodeTroubleCodes("NO DATA"))
    }

    @Test fun `pid command strings`() {
        assertEquals("010C", ObdPid.ENGINE_RPM.command)
        assertEquals("0142", ObdPid.CONTROL_MODULE_VOLTAGE.command)
    }
}
