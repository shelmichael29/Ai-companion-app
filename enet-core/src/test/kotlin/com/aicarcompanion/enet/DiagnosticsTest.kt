package com.aicarcompanion.enet

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine-computer VIN read and module scan, against a simulated gateway. Replies are scripted per
 * module address, so the tests can state exactly who answered.
 */
class DiagnosticsTest {

    private val dme = F10Modules.DME.address
    private val gatewayRef = GatewayVinReference(Redaction.maskVin(Sim.VIN), Redaction.vinDigest(Sim.VIN))

    private fun vinReply(from: Int, vin: String) =
        Hsfz.frame(Hsfz.CTRL_DIAG, byteArrayOf(from.toByte(), Hsfz.TESTER_ADDRESS.toByte(), 0x62, 0xF1.toByte(), 0x90.toByte()) + vin.toByteArray())

    private fun positive(from: Int, vararg uds: Int) = Sim.diag(from, Hsfz.TESTER_ADDRESS, *uds)

    private fun negative(from: Int, sid: Int, nrc: Int) = positive(from, 0x7F, sid, nrc)

    /** A simulated gateway: acknowledges every request, then answers per target address (or stays silent). */
    private class Gateway(val reply: (target: Int, uds: ByteArray) -> List<Pair<Long, ByteArray>>) {
        fun responder(): (ByteArray) -> List<Pair<Long, ByteArray>> = { written ->
            val target = written[Hsfz.HEADER_SIZE + 1].toInt() and 0xFF
            val uds = written.copyOfRange(Hsfz.HEADER_SIZE + 2, written.size)
            listOf(10L to Sim.ack(written)) + reply(target, uds)
        }
    }

    private fun TestScope.setup(reply: (Int, ByteArray) -> List<Pair<Long, ByteArray>>): Pair<FakeTransport, EnetTester> {
        val clock = { currentTime }
        val fake = FakeTransport(clock)
        fake.tcpConnect[Hsfz.DIAG_PORT] = FakeTransport.Connect.OK to 20L
        fake.tcpResponder[Hsfz.DIAG_PORT] = Gateway(reply).responder()
        return fake to EnetTester(fake, clock)
    }

    private fun EnetTester.exported() = log.export()

    // =====================================================================================================
    // Engine computer VIN
    // =====================================================================================================

    @Test fun `a valid VIN from the DME is read, shows the module, and matches the gateway's VIN`() = runTest {
        val (fake, tester) = setup { target, _ -> if (target == dme) listOf(60L to vinReply(dme, Sim.VIN)) else emptyList() }
        val r = tester.readEngineVin(Sim.LINK, Sim.CAR, EngineVinOptions(gatewayVin = gatewayRef)) as EngineVinResult.Success
        assertEquals(Sim.VIN, r.vin)
        assertEquals("DME", r.module.abbreviation)
        assertEquals(0x12, r.module.address)
        assertEquals(VinMatch.MATCHES_GATEWAY, r.match)
        assertEquals(1, fake.tcpClosed)
        // The request is the documented read-only one, addressed from the tester to the DME.
        val sent = fake.tcpWritten.single().second
        assertEquals("00 00 00 05 00 01 F4 12 22 F1 90", sent.joinToString(" ") { "%02X".format(it) })
    }

    @Test fun `a VIN that differs from the gateway's is reported as a mismatch, still a successful read`() = runTest {
        val other = "WBAFR1C52BC999999"
        val (_, tester) = setup { target, _ -> if (target == dme) listOf(60L to vinReply(dme, other)) else emptyList() }
        val r = tester.readEngineVin(Sim.LINK, Sim.CAR, EngineVinOptions(gatewayVin = gatewayRef)) as EngineVinResult.Success
        assertEquals(other, r.vin)
        assertEquals(VinMatch.DIFFERS_FROM_GATEWAY, r.match)
        assertEquals(gatewayRef.masked, r.gatewayMaskedVin)
    }

    @Test fun `without a gateway VIN the comparison says so instead of guessing`() = runTest {
        val (_, tester) = setup { target, _ -> if (target == dme) listOf(60L to vinReply(dme, Sim.VIN)) else emptyList() }
        assertEquals(VinMatch.NO_GATEWAY_VIN, (tester.readEngineVin(Sim.LINK, Sim.CAR) as EngineVinResult.Success).match)
    }

    @Test fun `a gateway acknowledgment alone is never a successful VIN read`() = runTest {
        val (fake, tester) = setup { _, _ -> emptyList() } // acknowledges, nobody answers
        val r = tester.readEngineVin(Sim.LINK, Sim.CAR, EngineVinOptions(gatewayVin = gatewayRef))
        assertTrue(r.toString(), r is EngineVinResult.NoAnswer)
        assertTrue((r as EngineVinResult.NoAnswer).detail.contains("A gateway acknowledgment is not an answer from the module"))
        assertEquals(1, fake.tcpClosed)
    }

    @Test fun `a reply from the wrong module is ignored, even with a valid VIN`() = runTest {
        for (wrong in listOf(0x10, 0x29, 0x60)) {
            val (_, tester) = setup { target, _ -> if (target == dme) listOf(60L to vinReply(wrong, Sim.VIN)) else emptyList() }
            val r = tester.readEngineVin(Sim.LINK, Sim.CAR)
            assertTrue("reply from 0x%02X must not count: $r".format(wrong), r is EngineVinResult.NoAnswer)
            assertTrue((r as EngineVinResult.NoAnswer).detail.contains("0x%02X".format(wrong)))
        }
    }

    @Test fun `an explicit rejection from the DME is not a VIN read`() = runTest {
        val (_, tester) = setup { target, _ -> if (target == dme) listOf(60L to negative(dme, 0x22, 0x31)) else emptyList() }
        val r = tester.readEngineVin(Sim.LINK, Sim.CAR) as EngineVinResult.Rejected
        assertEquals(0x31, r.nrc)
        assertTrue(r.detail.contains("no VIN was read"))
    }

    @Test fun `a rejection from another module is not the DME's rejection`() = runTest {
        val (_, tester) = setup { target, _ -> if (target == dme) listOf(60L to negative(0x10, 0x22, 0x31)) else emptyList() }
        assertTrue(tester.readEngineVin(Sim.LINK, Sim.CAR) is EngineVinResult.NoAnswer)
    }

    @Test fun `replies that are not a valid 17-character VIN are not shown as a VIN`() = runTest {
        val bad = listOf("WBAFR1C51BC00000", "WBAFR1C51BC0000011", "WBAFR1C51BCO00001", "wbafr1c51bc000001", "WBAFR1C51BC00000\u0000")
        for (v in bad) {
            val (_, tester) = setup { target, _ -> if (target == dme) listOf(60L to vinReply(dme, v)) else emptyList() }
            val r = tester.readEngineVin(Sim.LINK, Sim.CAR)
            assertTrue("'$v' must not be accepted: $r", r is EngineVinResult.Unusable)
            assertFalse((r as EngineVinResult.Unusable).detail.contains(v.trim('\u0000')))
        }
    }

    @Test fun `a positive reply for a different data identifier is not a VIN`() = runTest {
        val wrongDid = Hsfz.frame(Hsfz.CTRL_DIAG, byteArrayOf(dme.toByte(), 0xF4.toByte(), 0x62, 0xF1.toByte(), 0x89.toByte()) + Sim.VIN.toByteArray())
        val (_, tester) = setup { target, _ -> if (target == dme) listOf(60L to wrongDid) else emptyList() }
        assertTrue(tester.readEngineVin(Sim.LINK, Sim.CAR) is EngineVinResult.NoAnswer)
    }

    @Test fun `a module that says it is working on it is waited for, a bounded number of times`() = runTest {
        val (_, tester) = setup { target, _ ->
            if (target == dme) listOf(60L to negative(dme, 0x22, 0x78), 2_000L to vinReply(dme, Sim.VIN)) else emptyList()
        }
        assertTrue(tester.readEngineVin(Sim.LINK, Sim.CAR, EngineVinOptions(responseTimeoutMillis = 3_000)) is EngineVinResult.Success)

        // "Pending" forever: gives up after two extensions and reports the module answered but never finished.
        val (_, tester2) = setup { target, _ ->
            if (target == dme) listOf(60L to negative(dme, 0x22, 0x78), 1_000L to negative(dme, 0x22, 0x78), 2_000L to negative(dme, 0x22, 0x78), 3_000L to negative(dme, 0x22, 0x78)) else emptyList()
        }
        val r = tester2.readEngineVin(Sim.LINK, Sim.CAR, EngineVinOptions(responseTimeoutMillis = 1_500))
        assertTrue(r.toString(), r is EngineVinResult.Rejected && r.nrc == 0x78)
        assertTrue("bounded: the wait ends", currentTime <= 20L + 4 * 1_500L)
    }

    @Test fun `silence times out within the bound and is not a VIN`() = runTest {
        val (fake, tester) = setup { _, _ -> emptyList() }
        val t0 = currentTime
        val r = tester.readEngineVin(Sim.LINK, Sim.CAR, EngineVinOptions(responseTimeoutMillis = 3_000))
        assertTrue(r is EngineVinResult.NoAnswer)
        assertEquals(3_000L, currentTime - t0 - 20L)
        assertEquals(1, fake.tcpClosed)
    }

    @Test fun `a gateway error means the request never reached the DME`() = runTest {
        val (_, tester) = setup { _, _ -> listOf(20L to Hsfz.frame(0x0043, ByteArray(0))) }
        val r = tester.readEngineVin(Sim.LINK, Sim.CAR) as EngineVinResult.NoAnswer
        assertTrue(r.detail.contains("0x0043"))
    }

    @Test fun `connection failures are reported, and the unreachable gateway sends nothing`() = runTest {
        val clock = { currentTime }
        val fake = FakeTransport(clock) // port 6801 refuses by default
        val r = EnetTester(fake, clock).readEngineVin(Sim.LINK, Sim.CAR)
        assertTrue(r is EngineVinResult.ConnectionFailed)
        assertTrue(fake.tcpWritten.isEmpty())
    }

    @Test fun `the cable coming out during the read releases the connection`() = runTest {
        val (fake, tester) = setup { _, _ -> emptyList() }
        fake.tcpDropAfterWrites = 0
        assertTrue(tester.readEngineVin(Sim.LINK, Sim.CAR) is EngineVinResult.ConnectionFailed)
        assertEquals(1, fake.tcpClosed)
        // And the tester is free again.
        fake.tcpDropAfterWrites = null
        assertTrue(tester.readEngineVin(Sim.LINK, Sim.CAR) !is EngineVinResult.Refused)
    }

    @Test fun `cancelling the read closes the connection and frees the tester`() = runTest {
        val (fake, tester) = setup { _, _ -> emptyList() }
        val job = launch { tester.readEngineVin(Sim.LINK, Sim.CAR, EngineVinOptions(responseTimeoutMillis = 3_000)) }
        advanceTimeBy(500); runCurrent()
        job.cancel(); runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(1, fake.tcpClosed)
        assertTrue(tester.exported().contains("Engine VIN read cancelled"))
        assertTrue("free again", tester.readEngineVin(Sim.LINK, Sim.CAR, EngineVinOptions(responseTimeoutMillis = 500)) !is EngineVinResult.Refused)
    }

    @Test fun `operations never overlap`() = runTest {
        val (_, tester) = setup { _, _ -> emptyList() }
        val first = async { tester.readEngineVin(Sim.LINK, Sim.CAR, EngineVinOptions(responseTimeoutMillis = 3_000)) }
        advanceTimeBy(100); runCurrent()
        assertEquals(EngineVinResult.Refused("Another test is already running."), tester.readEngineVin(Sim.LINK, Sim.CAR))
        assertTrue(tester.scanModules(Sim.LINK, Sim.CAR) is ModuleScanResult.Refused)
        first.await()
    }

    @Test fun `targets outside the car network are refused before any socket opens`() = runTest {
        val (fake, tester) = setup { _, _ -> emptyList() }
        val r = tester.readEngineVin(Sim.LINK, Ipv4Address.parse("8.8.8.8")!!)
        assertTrue(r is EngineVinResult.Refused)
        assertTrue(tester.scanModules(Sim.LINK, Ipv4Address.parse("8.8.8.8")!!) is ModuleScanResult.Refused)
        assertTrue(fake.tcpConnectAttempts.isEmpty())
    }

    @Test fun `the exported log never contains the full VIN, as text or as hex`() = runTest {
        val (_, tester) = setup { target, _ -> if (target == dme) listOf(60L to vinReply(dme, Sim.VIN)) else emptyList() }
        tester.readEngineVin(Sim.LINK, Sim.CAR, EngineVinOptions(gatewayVin = gatewayRef))
        val text = tester.exported()
        assertFalse(text, text.contains(Sim.VIN))
        assertFalse(text, text.replace(" ", "").contains(Sim.VIN.toByteArray().joinToString("") { "%02X".format(it) }))
        assertTrue(text, text.contains(Redaction.maskVin(Sim.VIN)))
        assertFalse(EngineVinResult.Success(F10Modules.DME, Sim.VIN, VinMatch.NO_GATEWAY_VIN, null, 1).toString().contains(Sim.VIN))
    }

    // =====================================================================================================
    // Module scan
    // =====================================================================================================

    private fun answerWith(map: Map<Int, ByteArray>): (Int, ByteArray) -> List<Pair<Long, ByteArray>> =
        { target, _ -> map[target]?.let { listOf(50L to it) }.orEmpty() }

    @Test fun `the scan separates positive replies, rejections and silence, and counts a rejection as an answer`() = runTest {
        val (fake, tester) = setup(
            answerWith(
                mapOf(
                    0x10 to positive(0x10, 0x7E, 0x00),
                    dme to positive(dme, 0x7E, 0x00),
                    0x29 to negative(0x29, 0x3E, 0x12),
                    // 0x30 (EPS): silent
                    // 0x40 (CAS): silent
                    0x60 to positive(0x60, 0x7E, 0x00),
                    // 0x78 (IHKA): silent
                    0x01 to negative(0x01, 0x3E, 0x11),
                ),
            ),
        )
        val r = tester.scanModules(Sim.LINK, Sim.CAR) as ModuleScanResult.Finished
        val byName = r.entries.associateBy { it.module.abbreviation }
        assertEquals(ScanOutcome.POSITIVE, byName.getValue("ZGW").outcome)
        assertEquals(ScanOutcome.POSITIVE, byName.getValue("DME").outcome)
        assertEquals(ScanOutcome.REJECTED, byName.getValue("DSC").outcome)
        assertEquals(0x12, byName.getValue("DSC").nrc)
        assertEquals(ScanOutcome.NO_RESPONSE, byName.getValue("EPS").outcome)
        assertEquals(ScanOutcome.NO_RESPONSE, byName.getValue("CAS").outcome)
        assertEquals(ScanOutcome.POSITIVE, byName.getValue("KOMBI").outcome)
        assertEquals(ScanOutcome.NO_RESPONSE, byName.getValue("IHKA").outcome)
        assertEquals(ScanOutcome.REJECTED, byName.getValue("ACSM").outcome)
        assertEquals("a rejection counts as an answer", 5, r.answered)
        assertEquals(ScanEnd.COMPLETE, r.end)
        // One connection, closed once, modules asked one at a time in list order, nothing but the allowlisted request.
        assertEquals(1, fake.tcpConnectAttempts.size)
        assertEquals(1, fake.tcpClosed)
        assertEquals(F10Modules.SCANNED.map { it.address }, fake.tcpWritten.map { it.second[Hsfz.HEADER_SIZE + 1].toInt() and 0xFF })
        assertTrue(fake.tcpWritten.all { it.second.copyOfRange(Hsfz.HEADER_SIZE + 2, it.second.size).contentEquals(byteArrayOf(0x3E, 0x00)) })
    }

    @Test fun `silence is described as no response, never as absent`() = runTest {
        val (_, tester) = setup { _, _ -> emptyList() }
        val r = tester.scanModules(Sim.LINK, Sim.CAR) as ModuleScanResult.Finished
        assertEquals(0, r.answered)
        for (e in r.entries) {
            assertEquals(ScanOutcome.NO_RESPONSE, e.outcome)
            assertTrue(e.detail.contains("Silence is not proof that a module is absent"))
            assertFalse(e.detail.lowercase().contains("not installed"))
            assertFalse(e.detail.lowercase().contains("not fitted"))
        }
        assertFalse(ScanOutcome.NO_RESPONSE.answered)
    }

    @Test fun `gateway acknowledgments alone never make a module answer`() = runTest {
        val (_, tester) = setup { _, _ -> emptyList() } // the gateway acknowledges every probe
        val r = tester.scanModules(Sim.LINK, Sim.CAR) as ModuleScanResult.Finished
        assertTrue(r.entries.all { it.outcome == ScanOutcome.NO_RESPONSE })
        assertTrue(r.entries.all { it.detail.contains("acknowledgment is not an answer from the module") })
    }

    @Test fun `a reply from the wrong module is not credited to the module being asked`() = runTest {
        // Asking the DSC is answered by the KOMBI.
        val (_, tester) = setup { target, _ -> if (target == 0x29) listOf(50L to positive(0x60, 0x7E, 0x00)) else emptyList() }
        val r = tester.scanModules(Sim.LINK, Sim.CAR) as ModuleScanResult.Finished
        val dsc = r.entries.first { it.module.abbreviation == "DSC" }
        assertEquals(ScanOutcome.NO_RESPONSE, dsc.outcome)
        assertTrue(dsc.detail.contains("0x60"))
        assertEquals(0, r.answered)
    }

    @Test fun `a late reply from one module is not credited to the next module's probe`() = runTest {
        // The DME answers 1.7 s late, while the DSC is being asked (1.5 s timeout).
        val (_, tester) = setup { target, _ -> if (target == dme) listOf(1_700L to positive(dme, 0x7E, 0x00)) else emptyList() }
        val r = tester.scanModules(Sim.LINK, Sim.CAR, ScanOptions(moduleTimeoutMillis = 1_500)) as ModuleScanResult.Finished
        assertEquals(ScanOutcome.NO_RESPONSE, r.entries.first { it.module.abbreviation == "DME" }.outcome)
        assertEquals(ScanOutcome.NO_RESPONSE, r.entries.first { it.module.abbreviation == "DSC" }.outcome)
    }

    @Test fun `every wait is bounded, so the whole scan is bounded`() = runTest {
        val (_, tester) = setup { _, _ -> emptyList() }
        val t0 = currentTime
        tester.scanModules(Sim.LINK, Sim.CAR, ScanOptions(moduleTimeoutMillis = 1_500))
        assertEquals(20L + 8 * 1_500L, currentTime - t0)
        // The per-module wait is clamped.
        val (_, t2) = setup { _, _ -> emptyList() }
        val s = currentTime
        t2.scanModules(Sim.LINK, Sim.CAR, ScanOptions(moduleTimeoutMillis = 10))
        assertEquals(20L + 8 * 500L, currentTime - s)
        val (_, t3) = setup { _, _ -> emptyList() }
        val u = currentTime
        t3.scanModules(Sim.LINK, Sim.CAR, ScanOptions(moduleTimeoutMillis = 99_999))
        assertEquals(20L + 8 * 3_000L, currentTime - u)
    }

    @Test fun `progress is reported module by module`() = runTest {
        val (_, tester) = setup(answerWith(mapOf(0x10 to positive(0x10, 0x7E, 0x00))))
        val seen = mutableListOf<Pair<Int, String?>>()
        tester.scanModules(Sim.LINK, Sim.CAR) { seen += it.entries.size to it.current?.abbreviation }
        assertEquals(F10Modules.SCANNED.map { it.abbreviation }, seen.mapNotNull { it.second })
        assertEquals(8 to null, seen.last())
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), seen.dropLast(1).map { it.first })
    }

    @Test fun `cancelling the scan stops it, closes the connection and frees the tester`() = runTest {
        val (fake, tester) = setup { _, _ -> emptyList() }
        val seen = mutableListOf<ScanProgress>()
        val job = launch { tester.scanModules(Sim.LINK, Sim.CAR) { seen += it } }
        advanceTimeBy(1_700); runCurrent() // into the second module
        job.cancel(); runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(1, fake.tcpClosed)
        assertTrue("stopped early: not all modules were asked", fake.tcpWritten.size < F10Modules.SCANNED.size)
        assertTrue(seen.isNotEmpty() && seen.last().entries.size < F10Modules.SCANNED.size)
        assertTrue(tester.exported().contains("Module scan cancelled; connection closed."))
        assertTrue("free again", tester.readEngineVin(Sim.LINK, Sim.CAR, EngineVinOptions(responseTimeoutMillis = 500)) !is EngineVinResult.Refused)
    }

    @Test fun `the cable coming out mid-scan stops the scan, closes the connection and marks the rest not checked`() = runTest {
        val (fake, tester) = setup(answerWith(mapOf(0x10 to positive(0x10, 0x7E, 0x00), dme to positive(dme, 0x7E, 0x00))))
        fake.tcpDropAfterWrites = 3 // ZGW, DME, DSC go out; the cable is gone for the fourth
        val r = tester.scanModules(Sim.LINK, Sim.CAR) as ModuleScanResult.Finished
        assertEquals(ScanEnd.CONNECTION_LOST, r.end)
        assertTrue(r.endDetail!!.contains("Connection"))
        assertEquals(3, r.entries.count { it.outcome != ScanOutcome.NOT_CHECKED })
        assertEquals(5, r.entries.count { it.outcome == ScanOutcome.NOT_CHECKED })
        assertEquals(F10Modules.SCANNED.size, r.entries.size)
        assertEquals(1, fake.tcpClosed)
    }

    @Test fun `a module that says it is working on it is waited for`() = runTest {
        val (_, tester) = setup { target, _ ->
            if (target == dme) listOf(50L to negative(dme, 0x3E, 0x78), 1_000L to positive(dme, 0x7E, 0x00)) else emptyList()
        }
        val r = tester.scanModules(Sim.LINK, Sim.CAR, ScanOptions(moduleTimeoutMillis = 1_500)) as ModuleScanResult.Finished
        assertEquals(ScanOutcome.POSITIVE, r.entries.first { it.module.abbreviation == "DME" }.outcome)
    }

    @Test fun `the result states exactly what was and wasn't checked`() = runTest {
        val (_, tester) = setup { _, _ -> emptyList() }
        val r = tester.scanModules(Sim.LINK, Sim.CAR) as ModuleScanResult.Finished
        val text = r.coverage.statement
        for (m in F10Modules.SCANNED) assertTrue(text, text.contains(m.abbreviation))
        assertTrue(text, text.startsWith("Checked 8 modules"))
        assertTrue(text, text.contains("Automatic transmission (EGS)"))
        assertTrue(text, text.contains("Silence is not proof that a module is absent"))
        assertTrue(F10Modules.SCANNED.all { it.source.isNotBlank() && it.name.isNotBlank() })
        assertEquals(F10Modules.SCANNED.size, F10Modules.SCANNED.map { it.address }.toSet().size)
    }

    @Test fun `the scan log never contains a VIN`() = runTest {
        val (_, tester) = setup { target, _ -> if (target == dme) listOf(50L to vinReply(dme, Sim.VIN)) else emptyList() }
        tester.scanModules(Sim.LINK, Sim.CAR)
        assertFalse(tester.exported().contains(Sim.VIN))
    }

    @Test fun `unreachable gateway or blocked target gives no scan entries`() = runTest {
        val clock = { currentTime }
        val fake = FakeTransport(clock)
        assertTrue(EnetTester(fake, clock).scanModules(Sim.LINK, Sim.CAR) is ModuleScanResult.ConnectionFailed)
        assertNull(fake.tcpWritten.firstOrNull())
    }
}
