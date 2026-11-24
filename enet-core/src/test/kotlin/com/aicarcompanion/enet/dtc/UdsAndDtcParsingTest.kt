package com.aicarcompanion.enet.dtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UdsAndDtcParsingTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun `read requests have the ISO 14229 layouts`() {
        assertEquals("19 01 FF", ReadRequest.dtcCount().uds.hexString())
        assertEquals("19 02 FF", ReadRequest.dtcList().uds.hexString())
        assertEquals("19 03", ReadRequest.snapshotIdentification().uds.hexString())
        assertEquals("19 04 48 0A 12 FF", ReadRequest.snapshotRecord(0x480A12).uds.hexString())
        assertEquals("19 06 48 0A 12 FF", ReadRequest.extendedData(0x480A12).uds.hexString())
        assertEquals("19 15", ReadRequest.permanentDtcs().uds.hexString())
        assertEquals("3E 00", ReadRequest.testerPresent().uds.hexString())
    }

    @Test fun `read requests are read-only and defensive copies`() {
        val r = ReadRequest.dtcList()
        r.uds[0] = 0x14
        assertEquals(0x19, r.sid)
        assertTrue(Uds.SID_CLEAR_DTC !in Uds.READ_ONLY_SERVICES)
    }

    @Test fun `the only clear command is 14 FF FF FF`() {
        assertEquals("14 FF FF FF", ClearCommand.ALL_GROUPS.uds.hexString())
    }

    @Test fun `fault count parses count format and mask`() {
        val c = (DtcParser.count(b(0x59, 0x01, 0xFF, 0x01, 0x01, 0x02)) as Parsed.Ok).value
        assertEquals(258, c.count)
        assertEquals("ISO 14229-1", c.formatName)
        assertEquals(0xFF, c.availabilityMask)
        assertTrue(DtcParser.count(b(0x59, 0x01, 0xFF)) is Parsed.Malformed)
    }

    @Test fun `fault list parses BMW six-digit codes without conversion`() {
        val list = (DtcParser.list(b(0x59, 0x02, 0xFF, 0x48, 0x0A, 0x12, 0x09, 0x00, 0x2A, 0x1F, 0x08)) as Parsed.Ok).value
        assertEquals(listOf("480A12", "002A1F"), list.map { it.hex })
        assertEquals(0x09, list[0].status.raw)
        assertTrue((DtcParser.list(b(0x59, 0x02, 0xFF)) as Parsed.Ok).value.isEmpty())
    }

    @Test fun `truncated or foreign fault lists are malformed, not guessed`() {
        assertTrue(DtcParser.list(b(0x59, 0x02, 0xFF, 0x48, 0x0A)) is Parsed.Malformed)
        assertTrue(DtcParser.list(b(0x59, 0x01, 0xFF)) is Parsed.Malformed)
        assertTrue(DtcParser.list(b(0x62, 0xF1, 0x90)) is Parsed.Malformed)
    }

    @Test fun `snapshot ids and detail headers parse and check the DTC`() {
        val ids = (DtcParser.snapshotIds(b(0x59, 0x03, 0x48, 0x0A, 0x12, 0x01)) as Parsed.Ok).value
        assertEquals(listOf(SnapshotId(0x480A12, 1)), ids)
        val ok = DtcParser.detailHeader(b(0x59, 0x06, 0x48, 0x0A, 0x12, 0x09, 0x01, 0x07), 0x06, 0x480A12)
        assertEquals(0x480A12, (ok as Parsed.Ok).value.code)
        assertTrue(DtcParser.detailHeader(b(0x59, 0x06, 0x00, 0x00, 0x01, 0x09), 0x06, 0x480A12) is Parsed.Malformed)
    }

    @Test fun `status byte maps to active stored and pending`() {
        assertEquals(listOf("Active", "Stored"), DtcStatus(0x09).labels)
        assertEquals(listOf("Stored"), DtcStatus(0x08).labels)
        assertEquals(listOf("Pending"), DtcStatus(0x04).labels)
        assertEquals(listOf("Active", "Stored", "Pending", "Warning lamp requested"), DtcStatus(0x8D).labels)
        assertEquals(listOf("Not active, stored or pending"), DtcStatus(0x50).labels)
        assertFalse(DtcStatus(0x08).testFailed)
    }

    @Test fun `NRC names and unsupported classification`() {
        assertEquals("serviceNotSupported", Uds.nrcName(0x11))
        assertTrue(Uds.isUnsupported(0x12))
        assertFalse(Uds.isUnsupported(0x22))
        assertFalse(Uds.isUnsupported(0x31))
    }

    @Test fun `no description source means no description`() {
        assertEquals(null, FaultDescriptions.NONE.describe(0x12, 0x480A12))
    }
}
