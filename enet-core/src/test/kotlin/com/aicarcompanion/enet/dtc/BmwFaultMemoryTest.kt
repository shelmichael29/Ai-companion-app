package com.aicarcompanion.enet.dtc

import com.aicarcompanion.enet.EnetTransport
import com.aicarcompanion.enet.F10Module
import com.aicarcompanion.enet.FakeTransport
import com.aicarcompanion.enet.Hsfz
import com.aicarcompanion.enet.Ipv4Address
import com.aicarcompanion.enet.Sim
import com.aicarcompanion.enet.TcpChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** All replies are simulated. These tests prove protocol handling, not behaviour on a real F10. */
class BmwFaultMemoryTest {
    private val confirmAll = ClearConfirmation(true, true, true, true)

    private fun TestScope.setup(): Triple<FakeTransport, SimCar, BmwFaultMemory> {
        val fake = FakeTransport { currentTime }
        val car = SimCar(fake)
        return Triple(fake, car, BmwFaultMemory(fake, { currentTime }, { 1_700_000_000_000 }))
    }

    private val dme = F10Module.DME
    private val dsc = F10Module.DSC
    private val kombi = F10Module.KOMBI
    private val frm = F10Module.FRM

    @Test fun `scan reads faults from every responding module and continues past silent ones`() = runTest {
        val (_, car, mem) = setup()
        car.ecus[dme.address] = SimCar.Ecu(mutableListOf(0x480A12 to 0x09, 0x2A8F00 to 0x08))
        car.ecus[dsc.address] = SimCar.Ecu()
        car.ecus[frm.address] = SimCar.Ecu(mutableListOf(0x800A10 to 0x04))
        val progress = mutableListOf<String>()
        val report = mem.scan(Sim.LINK, Sim.CAR, listOf(dme, kombi, dsc, frm), progress = { i, n, m -> progress += "$i/$n ${m.name}" })

        assertEquals(listOf("1/4 DME", "2/4 KOMBI", "3/4 DSC", "4/4 FRM"), progress)
        assertEquals(3, report.responding.size) // Whatever responds, not a hardcoded count.
        assertEquals(Presence.NO_RESPONSE, report.modules[1].presence)
        assertEquals(listOf("480A12", "2A8F00"), report.modules[0].faults!!.map { it.record.hex })
        assertEquals(emptyList<FaultEntry>(), report.modules[2].faults)
        assertEquals("No faults stored", report.modules[2].faultReadStatus)
        assertEquals(listOf(dme, frm), report.clearEligible)
        assertEquals(3, report.faultCount)
        // Physical addressing: each request went to its own module address only.
        assertTrue(car.requests.filter { it.second.startsWith("19 02") }.map { it.first }.containsAll(listOf(dme.address, dsc.address, frm.address)))
        // The silent module got exactly one request on its connection: no blind retry.
        assertEquals(1, car.requests.count { it.first == kombi.address })
    }

    @Test fun `snapshots and extended data are read and raw responses preserved`() = runTest {
        val (_, car, mem) = setup()
        car.ecus[dme.address] = SimCar.Ecu(mutableListOf(0x480A12 to 0x09), snapshots = mutableListOf(0x480A12 to 0x01))
        val m = mem.scan(Sim.LINK, Sim.CAR, listOf(dme)).modules.single()
        val f = m.faults!!.single()
        assertEquals(listOf(1), f.snapshotRecords)
        assertTrue(f.snapshot!!.outcome is UdsOutcome.Positive)
        assertEquals("59 04 48 0A 12 09 01 01 10 05 0C 80", f.snapshot!!.rawResponse)
        assertEquals("59 06 48 0A 12 09 01 07", f.extended!!.rawResponse)
        assertNull(f.description)
        assertEquals(listOf("3E 00", "19 01 FF", "19 02 FF", "19 03", "19 04 48 0A 12 FF", "19 06 48 0A 12 FF"), m.requests.map { it.request })
    }

    @Test fun `unsupported extended data is reported once and not repeated for every fault`() = runTest {
        val (_, car, mem) = setup()
        car.ecus[dme.address] = SimCar.Ecu(mutableListOf(0x480A12 to 0x09, 0x2A8F00 to 0x08, 0x2A8F01 to 0x08), supportsExtended = false)
        val m = mem.scan(Sim.LINK, Sim.CAR, listOf(dme)).modules.single()
        assertEquals("Not supported", m.extendedStatus)
        assertEquals(1, car.requests.count { it.second.startsWith("19 06") })
        assertEquals(3, m.faults!!.size)
    }

    @Test fun `negative response to the fault list is reported with its NRC`() = runTest {
        val (_, car, mem) = setup()
        car.ecus[dme.address] = SimCar.Ecu(overrides = mutableMapOf("19 02" to listOf(2L to intArrayOf(0x7F, 0x19, 0x22))))
        val m = mem.scan(Sim.LINK, Sim.CAR, listOf(dme)).modules.single()
        assertNull(m.faults)
        assertTrue(m.faultReadStatus, m.faultReadStatus.contains("conditionsNotCorrect"))
        assertEquals("7F 19 22", m.requests.first { it.request == "19 02 FF" }.rawResponse)
        assertTrue(m.module !in mem.scan(Sim.LINK, Sim.CAR, listOf(dme)).clearEligible)
    }

    @Test fun `unsupported fault service is marked unsupported`() = runTest {
        val (_, car, mem) = setup()
        car.ecus[dme.address] = SimCar.Ecu(overrides = mutableMapOf("19" to listOf(2L to intArrayOf(0x7F, 0x19, 0x11))))
        val m = mem.scan(Sim.LINK, Sim.CAR, listOf(dme)).modules.single()
        assertTrue(m.faultReadStatus, m.faultReadStatus.startsWith("Not supported"))
    }

    @Test fun `response pending extends the wait until the final answer`() = runTest {
        val (_, car, mem) = setup()
        car.ecus[dme.address] = SimCar.Ecu(overrides = mutableMapOf("19 02" to listOf(
            100L to intArrayOf(0x7F, 0x19, 0x78), 2_900L to intArrayOf(0x7F, 0x19, 0x78),
            6_000L to intArrayOf(0x59, 0x02, 0xFF, 0x48, 0x0A, 0x12, 0x08),
        )))
        val m = mem.scan(Sim.LINK, Sim.CAR, listOf(dme), ScanOptions(includeDetails = false)).modules.single()
        assertEquals(listOf("480A12"), m.faults!!.map { it.record.hex })
    }

    @Test fun `a timeout closes that connection for further requests and the scan moves on`() = runTest {
        val (_, car, mem) = setup()
        car.ecus[dme.address] = SimCar.Ecu(mutableListOf(0x480A12 to 0x09), overrides = mutableMapOf("19 01" to emptyList()))
        car.ecus[dsc.address] = SimCar.Ecu(mutableListOf(0x5E0001 to 0x08))
        val report = mem.scan(Sim.LINK, Sim.CAR, listOf(dme, dsc))
        val d = report.modules[0]
        assertTrue(d.requests[1].outcome is UdsOutcome.Timeout)
        assertTrue(d.requests[2].outcome is UdsOutcome.NotSent)
        assertNull(d.faults)
        // Only TesterPresent and the timed-out 19 01 reached the DME; 19 02 was never sent on that stream.
        assertEquals(listOf("3E 00", "19 01 FF"), car.requests.filter { it.first == dme.address }.map { it.second })
        assertEquals(listOf("5E0001"), report.modules[1].faults!!.map { it.record.hex })
    }

    @Test fun `replies from another module or a late mismatched reply are ignored`() = runTest {
        val fake = FakeTransport { currentTime }
        fake.tcpConnect[Hsfz.DIAG_PORT] = FakeTransport.Connect.OK to 1L
        fake.tcpResponder[Hsfz.DIAG_PORT] = { req ->
            val uds = req.copyOfRange(8, req.size).hexString()
            when {
                uds == "3E 00" -> listOf(1L to Sim.diag(0x12, 0xF4, 0x7E, 0x00))
                uds == "19 02 FF" -> listOf(
                    1L to Sim.diag(0x29, 0xF4, 0x59, 0x02, 0xFF, 0x11, 0x11, 0x11, 0x08), // wrong ECU
                    2L to Sim.diag(0x12, 0xF4, 0x59, 0x01, 0xFF, 0x01, 0x00, 0x05), // late answer to 19 01
                    3L to Sim.diag(0x12, 0xF4, 0x59, 0x02, 0xFF, 0x48, 0x0A, 0x12, 0x08),
                )
                uds == "19 01 FF" -> listOf(1L to Sim.diag(0x12, 0xF4, 0x59, 0x01, 0xFF, 0x01, 0x00, 0x01))
                else -> emptyList()
            }
        }
        val m = BmwFaultMemory(fake, { currentTime }).scan(Sim.LINK, Sim.CAR, listOf(dme), ScanOptions(includeDetails = false)).modules.single()
        assertEquals(listOf("480A12"), m.faults!!.map { it.record.hex })
    }

    @Test fun `gateway routing errors and connection failures are per module`() = runTest {
        val (fake, car, _) = setup()
        car.gatewayErrors[kombi.address] = 0x0043
        car.ecus[dsc.address] = SimCar.Ecu()
        var n = 0
        val flaky = object : EnetTransport by fake {
            override suspend fun connectTcp(local: Ipv4Address, target: Ipv4Address, port: Int, timeoutMillis: Long): TcpChannel {
                if (n++ == 0) throw IOException("Connection reset")
                return fake.connectTcp(local, target, port, timeoutMillis)
            }
        }
        val report = BmwFaultMemory(flaky, { currentTime }).scan(Sim.LINK, Sim.CAR, listOf(dme, kombi, dsc))
        assertEquals(listOf(Presence.TRANSPORT_ERROR, Presence.GATEWAY_ERROR, Presence.RESPONDED), report.modules.map { it.presence })
    }

    @Test fun `targets outside the car network are refused before connecting`() = runTest {
        val (fake, _, mem) = setup()
        try {
            mem.scan(Sim.LINK, Ipv4Address.parse("8.8.8.8")!!, listOf(dme)); fail()
        } catch (expected: IOException) {
        }
        assertTrue(fake.tcpConnectAttempts.isEmpty())
    }

    @Test fun `cancelling a scan stops before the next module`() = runTest {
        val (_, car, mem) = setup()
        F10Module.entries.forEach { car.ecus[it.address] = SimCar.Ecu() }
        val seen = mutableListOf<F10Module>()
        val job = launch { mem.scan(Sim.LINK, Sim.CAR, result = { seen += it.module }) }
        advanceTimeBy(30)
        job.cancel()
        job.join()
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.size < F10Module.entries.size)
        // At most the module in progress was contacted beyond the completed ones.
        assertTrue(car.requests.map { it.first }.distinct().size <= seen.size + 1)
    }

    // ---- Clear gate ----

    private suspend fun TestScope.scannedReport(car: SimCar, mem: BmwFaultMemory, modules: List<F10Module>): DiagnosticReport =
        mem.scan(Sim.LINK, Sim.CAR, modules)

    @Test fun `clear is refused until the report is saved and every warning is confirmed`() = runTest {
        val (_, car, mem) = setup()
        car.ecus[dme.address] = SimCar.Ecu(mutableListOf(0x480A12 to 0x08))
        val report = scannedReport(car, mem, listOf(dme))
        val gate = ClearGate({ currentTime })

        val unsaved = gate.authorize(report, null, listOf(dme), confirmAll) as ClearDecision.Refused
        assertTrue(unsaved.reasons.any { it.contains("Save") })
        val noEmissions = gate.authorize(report, report.id, listOf(dme), confirmAll.copy(emissionsReadinessAccepted = false)) as ClearDecision.Refused
        assertTrue(noEmissions.reasons.any { it.contains("emissions") })
        for (c in listOf(confirmAll.copy(freezeFrameLossAccepted = false), confirmAll.copy(faultsMayRemainAccepted = false),
            confirmAll.copy(stationaryIgnitionOnEngineOff = false))) {
            assertTrue(gate.authorize(report, report.id, listOf(dme), c) is ClearDecision.Refused)
        }
        assertTrue(gate.authorize(report, "another-report", listOf(dme), confirmAll) is ClearDecision.Refused)
        assertTrue(gate.authorize(report, report.id, emptyList(), confirmAll) is ClearDecision.Refused)
        assertTrue(gate.authorize(report, report.id, listOf(dme), confirmAll) is ClearDecision.Authorized)
        assertEquals(0, car.clears(dme.address)) // Authorizing sends nothing.
    }

    @Test fun `emissions warning is only required when the engine computer is included`() = runTest {
        val (_, car, mem) = setup()
        car.ecus[frm.address] = SimCar.Ecu(mutableListOf(0x800A10 to 0x08))
        val report = scannedReport(car, mem, listOf(frm))
        assertTrue(ClearGate({ currentTime }).authorize(report, report.id, listOf(frm), confirmAll.copy(emissionsReadinessAccepted = false)) is ClearDecision.Authorized)
    }

    @Test fun `modules without a successful fault read are not eligible`() = runTest {
        val (_, car, mem) = setup()
        car.ecus[dsc.address] = SimCar.Ecu()
        val report = scannedReport(car, mem, listOf(dsc, kombi))
        val refused = ClearGate({ currentTime }).authorize(report, report.id, listOf(dsc, kombi), confirmAll) as ClearDecision.Refused
        assertEquals(2, refused.reasons.count { it.contains("isn't eligible") })
    }

    @Test fun `an authorization is single use and expires`() = runTest {
        val (_, car, mem) = setup()
        car.ecus[frm.address] = SimCar.Ecu(mutableListOf(0x800A10 to 0x08))
        val report = scannedReport(car, mem, listOf(frm))
        val gate = ClearGate({ currentTime })
        val auth = (gate.authorize(report, report.id, listOf(frm), confirmAll) as ClearDecision.Authorized).authorization
        mem.clear(Sim.LINK, Sim.CAR, auth)
        try { mem.clear(Sim.LINK, Sim.CAR, auth); fail() } catch (expected: IllegalStateException) {}
        assertEquals(1, car.clears(frm.address))

        car.ecus[frm.address]!!.faults += 0x800A10 to 0x08
        val report2 = scannedReport(car, mem, listOf(frm))
        val late = (gate.authorize(report2, report2.id, listOf(frm), confirmAll) as ClearDecision.Authorized).authorization
        advanceTimeBy(ClearGate.DEFAULT_VALIDITY_MILLIS + 1)
        try { mem.clear(Sim.LINK, Sim.CAR, late); fail() } catch (expected: IllegalStateException) {}
        assertEquals(1, car.clears(frm.address))
    }

    // ---- Clear and verification ----

    @Test fun `clear all reports partial results honestly and never retries`() = runTest {
        val (_, car, mem) = setup()
        car.ecus[dme.address] = SimCar.Ecu(mutableListOf(0x480A12 to 0x08)) // clears cleanly
        car.ecus[dsc.address] = SimCar.Ecu(mutableListOf(0x5E0001 to 0x09), returnAfterClear = mutableListOf(0x5E0001 to 0x09))
        car.ecus[frm.address] = SimCar.Ecu(mutableListOf(0x800A10 to 0x08), clearReply = intArrayOf(0x7F, 0x14, 0x22))
        val report = scannedReport(car, mem, listOf(dme, dsc, frm))
        val auth = (ClearGate({ currentTime }).authorize(report, report.id, report.clearEligible, confirmAll) as ClearDecision.Authorized).authorization
        val result = mem.clear(Sim.LINK, Sim.CAR, auth)

        assertFalse(result.allCleared)
        assertTrue(result.summary(), result.summary().startsWith("Not fully cleared: 1 of 3"))
        val (d, s, f) = result.results
        assertEquals(ClearStatus.CLEARED_VERIFIED, d.status)
        assertEquals(listOf("480A12"), d.verification!!.removed.map { it.hex })
        assertEquals(ClearStatus.CLEARED_FAULTS_REMAIN, s.status)
        assertEquals(listOf("5E0001"), s.verification!!.returnedActive.map { it.hex })
        assertEquals(ClearStatus.REJECTED, f.status)
        assertEquals("7F 14 22", f.clear.rawResponse)
        assertNull(f.verification)
        listOf(dme, dsc, frm).forEach { assertEquals(1, car.clears(it.address)) }
    }

    @Test fun `successful clear of a selected module is verified by a fresh read after a pause`() = runTest {
        val (_, car, mem) = setup()
        car.ecus[dme.address] = SimCar.Ecu(mutableListOf(0x480A12 to 0x08))
        car.ecus[frm.address] = SimCar.Ecu(mutableListOf(0x800A10 to 0x08))
        val report = scannedReport(car, mem, listOf(dme, frm))
        val auth = (ClearGate({ currentTime }).authorize(report, report.id, listOf(dme), confirmAll) as ClearDecision.Authorized).authorization
        val before = car.requests.size
        val started = currentTime
        val result = mem.clear(Sim.LINK, Sim.CAR, auth)
        assertTrue(result.allCleared)
        assertTrue(currentTime - started >= BmwFaultMemory.DEFAULT_SETTLE_MILLIS)
        assertEquals(listOf("14 FF FF FF", "19 02 FF", "19 15"), car.requests.drop(before).map { it.second })
        assertEquals(0, car.clears(frm.address)) // Only the selected module.
        assertNull(result.results.single().verification!!.permanent) // 19 15 unsupported here.
    }

    @Test fun `permanent faults and new faults are identified when supported`() = runTest {
        val (_, car, mem) = setup()
        car.ecus[dme.address] = SimCar.Ecu(
            mutableListOf(0x480A12 to 0x08), supportsPermanent = true, permanent = mutableListOf(0x480A12 to 0x08),
            returnAfterClear = mutableListOf(0x2A8F00 to 0x01),
        )
        val report = scannedReport(car, mem, listOf(dme))
        val auth = (ClearGate({ currentTime }).authorize(report, report.id, listOf(dme), confirmAll) as ClearDecision.Authorized).authorization
        val r = mem.clear(Sim.LINK, Sim.CAR, auth).results.single()
        assertEquals(ClearStatus.CLEARED_FAULTS_REMAIN, r.status)
        assertEquals(listOf("480A12"), r.verification!!.permanent!!.map { it.hex })
        assertEquals(listOf("480A12"), r.verification!!.stillStored.map { it.hex })
        assertEquals(listOf("2A8F00"), r.verification!!.newFaults.map { it.hex })
    }

    @Test fun `no answer to clear is reported and not retried`() = runTest {
        val (_, car, mem) = setup()
        car.ecus[dme.address] = SimCar.Ecu(mutableListOf(0x480A12 to 0x08), overrides = mutableMapOf("14" to emptyList()))
        val report = scannedReport(car, mem, listOf(dme))
        val auth = (ClearGate({ currentTime }).authorize(report, report.id, listOf(dme), confirmAll) as ClearDecision.Authorized).authorization
        val r = mem.clear(Sim.LINK, Sim.CAR, auth)
        assertEquals(ClearStatus.NO_RESPONSE, r.results.single().status)
        assertFalse(r.allCleared)
        assertEquals(1, car.clears(dme.address))
    }

    @Test fun `report text keeps raw codes, marks missing descriptions and has no VIN`() = runTest {
        val (_, car, mem) = setup()
        car.ecus[dme.address] = SimCar.Ecu(mutableListOf(0x480A12 to 0x09))
        val text = ReportFormatter.format(scannedReport(car, mem, listOf(dme)), "2026-11-24 10:00")
        assertTrue(text.contains("480A12 · Active, Stored (status 0x09)"))
        assertTrue(text.contains("not available (no verified source)"))
        assertTrue(text.contains("← [59 02 FF 48 0A 12 09]"))
        assertFalse(text.contains(Sim.VIN))
        assertFalse(Regex("\\bP[0-9A-F]{4}\\b").containsMatchIn(text))
    }
}
