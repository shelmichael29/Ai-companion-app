package com.aicarcompanion.enet

import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnetCoreTest {

    private fun TestScope.setup(): Pair<FakeTransport, EnetTester> {
        val clock = { currentTime }
        val fake = FakeTransport(clock)
        return fake to EnetTester(fake, clock)
    }

    // ---- Addresses and policy --------------------------------------------------------------------

    @Test fun `parses strict dotted quads only`() {
        assertEquals("169.254.1.2", Ipv4Address.parse(" 169.254.1.2 ").toString())
        listOf("", "1.2.3", "1.2.3.4.5", "256.1.1.1", "01.2.3.4", "a.b.c.d", "1.2.3.-4", "example.com").forEach {
            assertNull(it, Ipv4Address.parse(it))
        }
    }

    @Test fun `subnet math`() {
        val s = Ipv4Subnet(Ipv4Address.parse("169.254.10.2")!!, 16)
        assertEquals("169.254.255.255", s.broadcast.toString())
        assertEquals("169.254.0.0", s.network.toString())
        assertTrue(Ipv4Address.parse("169.254.42.17")!! in s)
        assertFalse(Ipv4Address.parse("169.253.1.1")!! in s)
        assertEquals("192.168.1.255", Ipv4Subnet(Ipv4Address.parse("192.168.1.40")!!, 24).broadcast.toString())
    }

    @Test fun `only private or link-local targets on the selected link are allowed`() {
        val link = Sim.LINK.subnet
        assertEquals(TargetPolicy.Verdict.Allowed, TargetPolicy.checkTarget(Sim.CAR, link))
        listOf("8.8.8.8", "127.0.0.1", "224.0.0.1", "255.255.255.255", "0.0.0.0", "169.254.10.2", "169.254.255.255", "169.254.0.0", "192.168.1.1", "10.0.0.1")
            .forEach { assertTrue(it, TargetPolicy.checkTarget(Ipv4Address.parse(it)!!, link) is TargetPolicy.Verdict.Rejected) }
        val home = Ipv4Subnet(Ipv4Address.parse("192.168.8.20")!!, 24)
        assertEquals(TargetPolicy.Verdict.Allowed, TargetPolicy.checkTarget(Ipv4Address.parse("192.168.8.1")!!, home))
    }

    @Test fun `the phone's own interface must be private or link-local and not huge`() {
        assertEquals(TargetPolicy.Verdict.Allowed, TargetPolicy.checkLocal(Sim.LINK.subnet))
        assertTrue(TargetPolicy.checkLocal(Ipv4Subnet(Ipv4Address.parse("81.2.3.4")!!, 24)) is TargetPolicy.Verdict.Rejected)
        assertTrue(TargetPolicy.checkLocal(Ipv4Subnet(Ipv4Address.parse("10.1.2.3")!!, 8)) is TargetPolicy.Verdict.Rejected)
        assertTrue(TargetPolicy.checkLocal(Ipv4Subnet(Ipv4Address(0), 16)) is TargetPolicy.Verdict.Rejected)
    }

    // ---- HSFZ framing ------------------------------------------------------------------------------

    @Test fun `ident request matches the documented bytes`() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0x11), Hsfz.IDENT_REQUEST)
        assertArrayEquals(byteArrayOf(0, 0, 0, 2, 0, 0x13, 0, 0xF4.toByte()), Hsfz.ALIVE_RESPONSE)
    }

    @Test fun `TesterPresent is framed with tester and gateway addresses`() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 4, 0, 1, 0xF4.toByte(), 0x10, 0x3E, 0x00), Hsfz.diagnosticFrame(SafeRequest.TESTER_PRESENT))
    }

    @Test fun `only fixed read-only requests exist`() {
        assertEquals(listOf(SafeRequest.READ_VIN, SafeRequest.TESTER_PRESENT), SafeRequest.entries)
        // No service that writes, resets, clears codes, controls actuators or programs.
        val forbidden = setOf(0x04, 0x10, 0x11, 0x14, 0x27, 0x28, 0x2E, 0x2F, 0x31, 0x34, 0x36, 0x37, 0x3D, 0x85)
        SafeRequest.entries.forEach { assertFalse(it.name, (it.uds[0].toInt() and 0xFF) in forbidden) }
    }

    @Test fun `frame reader reassembles split and joined frames`() {
        val a = Sim.diag(0x10, 0xF4, 0x7E, 0x00)
        val b = Hsfz.frame(Hsfz.CTRL_ALIVE_CHECK, ByteArray(0))
        val stream = a + b
        val reader = HsfzFrameReader()
        val frames = mutableListOf<HsfzFrame>()
        stream.forEach { frames += reader.feed(byteArrayOf(it)) } // one byte at a time
        assertEquals(listOf(Hsfz.CTRL_DIAG, Hsfz.CTRL_ALIVE_CHECK), frames.map { it.control })
        assertEquals(2, HsfzFrameReader().feed(stream).size)
    }

    @Test(expected = HsfzProtocolException::class)
    fun `frame reader refuses oversized frames`() {
        HsfzFrameReader().feed(byteArrayOf(0x7F, 0, 0, 0, 0, 1))
    }

    @Test fun `parses HSFZ identification answers`() {
        val id = HsfzIdentParser.parse(Sim.hsfzIdentReply().data)!!
        assertEquals("10", id.diagAddress)
        assertEquals("001A37A1B2C3", id.mac)
        assertEquals(Sim.VIN, id.vin)
        assertNull(HsfzIdentParser.parse(byteArrayOf(0, 0, 0, 0, 0, 0x11)))
        assertNull(HsfzIdentParser.parse(Hsfz.frame(0x11, "HELLO WORLD THERE".toByteArray())))
    }

    // ---- DoIP ----------------------------------------------------------------------------------------

    @Test fun `DoIP request and announcement`() {
        assertArrayEquals(byteArrayOf(0x02, 0xFD.toByte(), 0x00, 0x01, 0, 0, 0, 0), Doip.VEHICLE_ID_REQUEST)
        val a = DoipParser.parseAnnouncement(Sim.doipAnnouncement().data)!!
        assertEquals(Sim.VIN, a.vin)
        assertEquals(0x0010, a.logicalAddress)
        assertNull(DoipParser.parseAnnouncement(Doip.VEHICLE_ID_REQUEST))
        val bad = Sim.doipAnnouncement().data.copyOf().also { it[1] = 0 }
        assertNull("inverse version byte is checked", DoipParser.parseAnnouncement(bad))
    }

    // ---- Redaction -----------------------------------------------------------------------------------

    @Test fun `logs never contain a full VIN or MAC`() {
        assertEquals("WBA********000001", Redaction.maskVin(Sim.VIN))
        assertEquals("001A37******", Redaction.maskMac("001A37A1B2C3"))
        val text = Redaction.redact("VIN ${Sim.VIN} MAC 001A37A1B2C3 mac 00:1a:37:a1:b2:c3 ip 169.254.42.17")
        assertFalse(text.contains(Sim.VIN)); assertFalse(text.contains("A1B2C3")); assertFalse(text.contains("a1:b2:c3"))
        assertTrue(text.contains("169.254.42.17"))
    }

    // ---- Discovery -----------------------------------------------------------------------------------

    @Test fun `discovers an F10 gateway over HSFZ and notes no DoIP`() = runTest {
        val (fake, tester) = setup()
        fake.udpReplies[Ipv4Address.parse("169.254.255.255")!! to Hsfz.IDENT_PORT] = listOf(35L to Sim.hsfzIdentReply())
        val r = tester.discover(Sim.LINK, DiscoveryOptions(includeDoip = true)) as DiscoveryResult.Completed
        assertEquals(1, r.gateways.size)
        val gw = r.gateways.single()
        assertEquals(Sim.CAR, gw.address)
        assertEquals(GatewayProtocol.HSFZ, gw.protocol)
        assertEquals(35L, gw.latencyMillis)
        assertEquals("WBA********000001", gw.maskedVin)
        assertEquals(4, r.probesSent) // subnet + limited broadcast, HSFZ + DoIP each
        assertEquals("discovery is bounded by the (default 9 s) listen window", 9_000L, currentTime)
        assertTrue(tester.log.entries.value.any { it.message.contains("No DoIP answer") })
        assertFalse(tester.log.export().contains(Sim.VIN))
    }

    @Test fun `duplicate answers are merged and DoIP gateways are recognized`() = runTest {
        val (fake, tester) = setup()
        fake.udpReplies[Ipv4Address.LIMITED_BROADCAST to Hsfz.IDENT_PORT] = listOf(10L to Sim.hsfzIdentReply(), 20L to Sim.hsfzIdentReply())
        fake.udpReplies[Ipv4Address.LIMITED_BROADCAST to Doip.PORT] = listOf(15L to Sim.doipAnnouncement())
        val r = tester.discover(Sim.LINK, DiscoveryOptions(includeDoip = true)) as DiscoveryResult.Completed
        assertEquals(setOf(GatewayProtocol.HSFZ, GatewayProtocol.DOIP), r.gateways.map { it.protocol }.toSet())
        assertEquals(2, r.gateways.size)
    }

    @Test fun `replies from outside the car network or garbage are ignored`() = runTest {
        val (fake, tester) = setup()
        fake.udpReplies[Ipv4Address.LIMITED_BROADCAST to Hsfz.IDENT_PORT] = listOf(
            5L to Sim.hsfzIdentReply(from = Ipv4Address.parse("8.8.8.8")!!),
            6L to Datagram(Sim.CAR, Hsfz.IDENT_PORT, byteArrayOf(1, 2, 3)),
            7L to Datagram(Sim.CAR, 9999, Sim.hsfzIdentReply().data),
        )
        val r = tester.discover(Sim.LINK) as DiscoveryResult.Completed
        assertTrue(r.gateways.isEmpty())
        assertEquals(3, r.ignoredPackets)
    }

    @Test fun `discovery stops at the response limit`() = runTest {
        val (fake, tester) = setup()
        fake.udpReplies[Ipv4Address.LIMITED_BROADCAST to Hsfz.IDENT_PORT] =
            (1..30).map { i -> i.toLong() to Sim.hsfzIdentReply(from = Ipv4Address.parse("169.254.1.$i")!!) }
        val r = tester.discover(Sim.LINK, DiscoveryOptions(includeDoip = false, maxResponses = 4)) as DiscoveryResult.Completed
        assertEquals(4, r.gateways.size)
        assertTrue(currentTime < 9_000)
    }

    @Test fun `manual target gets a unicast probe, invalid targets send nothing`() = runTest {
        val (fake, tester) = setup()
        fake.udpReplies[Sim.CAR to Hsfz.IDENT_PORT] = listOf(3L to Sim.hsfzIdentReply())
        val r = tester.discover(Sim.LINK, DiscoveryOptions(manualTarget = Sim.CAR, includeDoip = false)) as DiscoveryResult.Completed
        assertEquals(Sim.CAR, r.gateways.single().address)
        assertTrue(fake.udpSent.any { it.to == Sim.CAR })

        val (fake2, tester2) = setup()
        val refused = tester2.discover(Sim.LINK, DiscoveryOptions(manualTarget = Ipv4Address.parse("8.8.8.8")))
        assertTrue(refused is DiscoveryResult.Refused)
        assertEquals("no socket opened", 0, fake2.udpOpened)
    }

    @Test fun `discovery refuses a public or missing interface address without opening sockets`() = runTest {
        val (fake, tester) = setup()
        val publicLink = EnetLink("eth0", Ipv4Subnet(Ipv4Address.parse("81.2.3.4")!!, 24))
        assertTrue(tester.discover(publicLink) is DiscoveryResult.Refused)
        assertEquals(0, fake.udpOpened)
        fake.udpOpenFails = true
        assertTrue(tester.discover(Sim.LINK) is DiscoveryResult.Refused)
    }

    @Test fun `only one operation runs at a time`() = runTest {
        val (_, tester) = setup()
        val first = async { tester.discover(Sim.LINK) }
        runCurrent()
        assertTrue(tester.discover(Sim.LINK) is DiscoveryResult.Refused)
        assertTrue(first.await() is DiscoveryResult.Completed)
    }

    @Test fun `F10 discovery defaults to HSFZ only and waits nine seconds`() = runTest {
        val (fake, tester) = setup()
        val r = tester.discover(Sim.LINK) as DiscoveryResult.Completed
        assertEquals(2, r.probesSent)
        assertEquals(9_000L, r.listenMillis)
        assertEquals(9_000L, currentTime)
        assertTrue(fake.udpSent.all { it.port == Hsfz.IDENT_PORT })
    }

    // ---- Connection test ------------------------------------------------------------------------------

    private fun FakeTransport.gatewayAnswersTesterPresent(aliveCheckFirst: Boolean = false) {
        tcpConnect[Hsfz.DIAG_PORT] = FakeTransport.Connect.OK to 12L
        tcpResponder[Hsfz.DIAG_PORT] = { written ->
            if (written.contentEquals(Hsfz.diagnosticFrame(SafeRequest.TESTER_PRESENT))) buildList {
                if (aliveCheckFirst) add(5L to Hsfz.frame(Hsfz.CTRL_ALIVE_CHECK, ByteArray(0)))
                add(8L to Sim.ack(written))
                add(40L to Sim.diag(0x10, 0xF4, 0x7E, 0x00))
            } else emptyList()
        }
    }

    @Test fun `connection test passes with ack and positive TesterPresent response`() = runTest {
        val (fake, tester) = setup()
        fake.gatewayAnswersTesterPresent()
        val r = tester.testConnection(Sim.LINK, Sim.CAR) as ConnectionResult.Completed
        assertTrue(r.transportOk); assertTrue(r.sessionOk)
        assertEquals(12L, r.steps[0].latencyMillis)
        assertEquals(40L, r.steps[1].latencyMillis)
        assertTrue(r.steps[1].detail.contains("acknowledged"))
        assertEquals("only the fixed TesterPresent frame is ever written",
            listOf(Hsfz.diagnosticFrame(SafeRequest.TESTER_PRESENT).toList()), fake.tcpWritten.map { it.second.toList() })
        assertEquals(1, fake.tcpClosed)
    }

    @Test fun `alive checks from the gateway are answered`() = runTest {
        val (fake, tester) = setup()
        fake.gatewayAnswersTesterPresent(aliveCheckFirst = true)
        val r = tester.testConnection(Sim.LINK, Sim.CAR) as ConnectionResult.Completed
        assertTrue(r.sessionOk)
        assertTrue(fake.tcpWritten.any { it.second.contentEquals(Hsfz.ALIVE_RESPONSE) })
    }

    @Test fun `negative response still proves the session, NACK and silence fail`() = runTest {
        val (fake, tester) = setup()
        fake.tcpConnect[Hsfz.DIAG_PORT] = FakeTransport.Connect.OK to 5L
        fake.tcpResponder[Hsfz.DIAG_PORT] = { listOf(10L to Sim.diag(0x10, 0xF4, 0x7F, 0x3E, 0x22)) }
        val neg = tester.testConnection(Sim.LINK, Sim.CAR) as ConnectionResult.Completed
        assertTrue(neg.sessionOk); assertTrue(neg.steps[1].detail.contains("NRC 0x22"))

        fake.tcpResponder[Hsfz.DIAG_PORT] = { listOf(10L to Hsfz.frame(Hsfz.CTRL_NACK, ByteArray(0))) }
        val nack = tester.testConnection(Sim.LINK, Sim.CAR) as ConnectionResult.Completed
        assertEquals(StepOutcome.FAIL, nack.steps[1].outcome)

        fake.tcpResponder[Hsfz.DIAG_PORT] = { listOf(10L to Sim.ack(it)) }
        val start = currentTime
        val silent = tester.testConnection(Sim.LINK, Sim.CAR) as ConnectionResult.Completed
        assertEquals(StepOutcome.FAIL, silent.steps[1].outcome)
        assertTrue(silent.steps[1].detail.startsWith("Acknowledged"))
        assertTrue("bounded by the response timeout", currentTime - start <= 5L + 3_000L)
    }

    @Test fun `refused and timed-out connections are reported without a session attempt`() = runTest {
        val (fake, tester) = setup()
        fake.tcpConnect[Hsfz.DIAG_PORT] = FakeTransport.Connect.REFUSED to 2L
        val refused = tester.testConnection(Sim.LINK, Sim.CAR) as ConnectionResult.Completed
        assertEquals(StepOutcome.FAIL, refused.steps[0].outcome)
        assertEquals(StepOutcome.SKIPPED, refused.steps[1].outcome)

        fake.tcpConnect[Hsfz.DIAG_PORT] = FakeTransport.Connect.TIMEOUT to 0L
        val start = currentTime
        val timeout = tester.testConnection(Sim.LINK, Sim.CAR) as ConnectionResult.Completed
        assertTrue(timeout.steps[0].detail.startsWith("No answer"))
        assertEquals("default connect timeout is 5 s", 5_000L, currentTime - start)
        assertTrue(fake.tcpWritten.isEmpty())
    }

    @Test fun `disallowed targets are refused before any connection`() = runTest {
        val (fake, tester) = setup()
        for (t in listOf("8.8.8.8", "127.0.0.1", "169.254.255.255", "192.168.0.10")) {
            assertTrue(t, tester.testConnection(Sim.LINK, Ipv4Address.parse(t)!!) is ConnectionResult.Refused)
        }
        assertTrue(fake.tcpConnectAttempts.isEmpty())
    }

    @Test fun `optional ignition query and DoIP port check`() = runTest {
        val (fake, tester) = setup()
        fake.gatewayAnswersTesterPresent()
        fake.tcpConnect[Hsfz.CONTROL_PORT] = FakeTransport.Connect.OK to 3L
        fake.tcpResponder[Hsfz.CONTROL_PORT] = { listOf(4L to Hsfz.frame(Hsfz.CTRL_IGNITION, byteArrayOf(0x05))) }
        val r = tester.testConnection(Sim.LINK, Sim.CAR, ConnectionOptions(queryIgnition = true, checkDoipPort = true)) as ConnectionResult.Completed
        val ign = r.steps.first { it.name == ConnectionResult.STEP_IGNITION }
        assertEquals(StepOutcome.PASS, ign.outcome); assertTrue(ign.detail.contains("on"))
        val doip = r.steps.first { it.name == ConnectionResult.STEP_DOIP }
        assertEquals("F10 has no DoIP: reported as not supported, not a failure", StepOutcome.NOT_SUPPORTED, doip.outcome)
        assertTrue(fake.tcpWritten.filter { it.first == Hsfz.CONTROL_PORT }.all { it.second.contentEquals(Hsfz.IGNITION_REQUEST) })
        assertTrue("no DoIP bytes are sent", fake.tcpWritten.none { it.first == Doip.PORT })
    }

    @Test fun `HSFZ gateway error frames report specific failures rather than silence`() = runTest {
        val causes = mapOf(
            0x0040 to "wrong tester address",
            0x0041 to "wrong control word",
            0x0042 to "wrong frame format",
            0x0043 to "unknown destination",
            0x0044 to "message too large",
            0x0045 to "gateway not ready",
        )
        causes.forEach { (code, cause) ->
            val (fake, tester) = setup()
            fake.tcpConnect[Hsfz.DIAG_PORT] = FakeTransport.Connect.OK to 5L
            fake.tcpResponder[Hsfz.DIAG_PORT] = { request ->
                listOf(2L to Sim.ack(request), 10L to Hsfz.frame(code, ByteArray(0)))
            }
            val result = tester.testConnection(Sim.LINK, Sim.CAR) as ConnectionResult.Completed
            assertTrue(result.transportOk)
            assertEquals(StepOutcome.FAIL, result.steps[1].outcome)
            assertTrue(result.steps[1].detail, result.steps[1].detail.contains(cause))
            assertTrue(result.steps[1].detail.contains("0x%04X".format(code)))
            assertEquals(1, fake.tcpClosed)
        }
    }

    @Test fun `TesterPresent can be turned off - TCP check only`() = runTest {
        val (fake, tester) = setup()
        fake.gatewayAnswersTesterPresent()
        val r = tester.testConnection(Sim.LINK, Sim.CAR, ConnectionOptions(sendTesterPresent = false)) as ConnectionResult.Completed
        assertTrue(r.transportOk)
        assertEquals(StepOutcome.SKIPPED, r.steps[1].outcome)
        assertTrue(fake.tcpWritten.isEmpty())
    }
}
