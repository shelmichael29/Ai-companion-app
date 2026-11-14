package com.aicarcompanion.app.diagnostics

import com.aicarcompanion.app.diagnostics.obd.BlockedCommandException
import com.aicarcompanion.app.diagnostics.obd.Elm327ResponseParser
import com.aicarcompanion.app.diagnostics.obd.Elm327Session
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import com.aicarcompanion.app.diagnostics.obd.ReadOnlyCommandPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdSafetyAndParsingTest {

    @Test fun `read-only requests are allowed`() {
        listOf("ATZ", "ATE0", "ATSP0", "ATDPN", "ATRV", "0100", "010C", "01 05", "03", "07", "0A", "0902", "020200")
            .forEach { assertTrue(it, ReadOnlyCommandPolicy.isAllowed(it)) }
    }

    @Test fun `destructive or arbitrary commands are blocked`() {
        listOf(
            "04", // clear DTCs
            "0800", // on-board control
            "14FFFFFF", // UDS clear diagnostic information
            "1101", // ECU reset
            "2E F1 90 00", // write data by identifier
            "2F 01 02 03", // I/O control (actuator test)
            "3101FF00", // routine control
            "2701", // security access
            "ATSH7E0", // custom header
            "ATMA", // monitor all
            "ATPP 2C SV 00", // programmable parameters
            "", "0", "01", "01ZZ",
        ).forEach { assertFalse(it, ReadOnlyCommandPolicy.isAllowed(it)) }
    }

    @Test fun `blocked command never reaches the adapter`() = runTest {
        val adapter = FakeElmAdapter()
        val session = Elm327Session(adapter)
        val thrown = runCatching { session.execute("04") }.exceptionOrNull()
        assertTrue(thrown is BlockedCommandException)
        assertTrue(adapter.written.isEmpty())
    }

    @Test fun `multiple ECUs answering a pid`() {
        assertEquals(800.0, Elm327ResponseParser.decodePid("41 0C 0C 80\r41 0C 0C 82\r\r>", ObdPid.ENGINE_RPM)!!, 0.0)
    }

    @Test fun `supported pid bitmap merges ECUs`() {
        val pids = Elm327ResponseParser.decodeSupportedPids("41 00 BE 1F A8 13", 0x00)
        assertEquals(setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x20), pids)
        val merged = Elm327ResponseParser.decodeSupportedPids("41 00 80 00 00 00\r41 00 00 00 00 01", 0x00)
        assertEquals(setOf(0x01, 0x20), merged)
    }

    @Test fun `multi-frame CAN trouble code response`() {
        // 4 codes from one ECU: length 0x00A = 43, count 04, then 8 code bytes.
        val raw = "00A\r0: 43 04 01 33 04 20\r1: 01 71 03 00 00 00 00\r\r>"
        assertEquals(listOf("P0133", "P0420", "P0171", "P0300"), Elm327ResponseParser.decodeTroubleCodes(raw, isCan = true))
    }

    @Test fun `codes from two ECUs are merged`() {
        val raw = "43 01 01 71\r43 01 07 00"
        assertEquals(listOf("P0171", "P0700"), Elm327ResponseParser.decodeTroubleCodes(raw, isCan = true))
    }

    @Test fun `one ECU answering NO DATA does not hide another's answer`() {
        assertEquals(90.0, Elm327ResponseParser.decodePid("NO DATA\r41 05 82", ObdPid.COOLANT_TEMP)!!, 0.0)
    }

    @Test fun `k-line bus init line is ignored`() {
        assertEquals(50.0, Elm327ResponseParser.decodePid("BUS INIT: ...OK\r41 05 5A", ObdPid.COOLANT_TEMP)!!, 0.0)
        assertNull(Elm327ResponseParser.decodePid("BUS INIT: ...ERROR", ObdPid.COOLANT_TEMP))
    }

    @Test fun `adapter voltage`() {
        assertEquals(12.4, Elm327ResponseParser.decodeAdapterVoltage("12.4V\r\r>")!!, 0.0)
        assertNull(Elm327ResponseParser.decodeAdapterVoltage("?"))
    }

    @Test fun `echo and prompt are cleaned`() {
        assertEquals("OK", Elm327Session.clean("ATE0\rOK\r\r>", "ATE0"))
    }
}
