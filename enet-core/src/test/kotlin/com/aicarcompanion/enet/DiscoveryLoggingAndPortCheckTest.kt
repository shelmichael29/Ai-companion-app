package com.aicarcompanion.enet

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class DiscoveryLoggingAndPortCheckTest {

    private fun TestScope.setup(): Pair<FakeTransport, EnetTester> {
        val clock = { currentTime }
        val fake = FakeTransport(clock)
        return fake to EnetTester(fake, clock)
    }

    private fun EnetTester.logText() = log.entries.value.joinToString("\n") { it.message }

    // ---- Timeout -----------------------------------------------------------------------------------

    @Test fun `discovery listens 5 seconds by default and the window is configurable within bounds`() = runTest {
        val (fake, tester) = setup()
        fake.udpReplies[Ipv4Address.LIMITED_BROADCAST to Hsfz.IDENT_PORT] = listOf(4_200L to Sim.hsfzIdentReply())
        val r = tester.discover(Sim.LINK) as DiscoveryResult.Completed
        assertEquals("a reply after 4.2 s is caught with the 5 s default", 1, r.gateways.size)
        assertEquals(5_000L, r.listenMillis)
        assertEquals(5_000L, currentTime)

        val (fake2, tester2) = setup()
        fake2.udpReplies[Ipv4Address.LIMITED_BROADCAST to Hsfz.IDENT_PORT] = listOf(9_000L to Sim.hsfzIdentReply())
        val r2 = tester2.discover(Sim.LINK, DiscoveryOptions(listenMillis = 10_000, includeDoip = false)) as DiscoveryResult.Completed
        assertEquals(1, r2.gateways.size)
        assertEquals(10_000L, r2.listenMillis)

        val (_, tester3) = setup()
        assertEquals(15_000L, (tester3.discover(Sim.LINK, DiscoveryOptions(listenMillis = 999_999)) as DiscoveryResult.Completed).listenMillis)
        val (_, tester4) = setup()
        assertEquals(1_000L, (tester4.discover(Sim.LINK, DiscoveryOptions(listenMillis = 1)) as DiscoveryResult.Completed).listenMillis)
    }

    // ---- Packet and socket logging -----------------------------------------------------------------

    @Test fun `log records exact outgoing bytes, source, destination, interface and binding`() = runTest {
        val (fake, tester) = setup()
        tester.discover(Sim.LINK, DiscoveryOptions(includeDoip = false))
        val log = tester.logText()
        assertTrue(log, log.contains("UDP socket bound to 169.254.10.2:50123 on eth0; simulated network binding (eth0); SO_BROADCAST=true"))
        assertTrue(log, log.contains("→ UDP 169.254.10.2:50123 → 169.254.255.255:6811 via eth0: 6 bytes [00 00 00 00 00 11] (HSFZ vehicle identification)"))
        assertTrue(log, log.contains("→ UDP 169.254.10.2:50123 → 255.255.255.255:6811 via eth0: 6 bytes [00 00 00 00 00 11]"))
        // The packet on the wire is exactly the documented F-series request, to UDP 6811.
        assertTrue(fake.udpSent.all { it.port == 6811 && it.data.contentEquals(byteArrayOf(0, 0, 0, 0, 0, 0x11)) })
    }

    @Test fun `received packets are logged without exposing the VIN, even as hex`() = runTest {
        val (fake, tester) = setup()
        fake.udpReplies[Ipv4Address.LIMITED_BROADCAST to Hsfz.IDENT_PORT] = listOf(30L to Sim.hsfzIdentReply())
        fake.udpReplies[Ipv4Address.LIMITED_BROADCAST to Doip.PORT] = listOf(40L to Sim.doipAnnouncement())
        tester.discover(Sim.LINK)
        val log = tester.logText()
        assertTrue(log, log.contains("← UDP 169.254.42.17:6811 → 169.254.10.2:50123 after 30 ms: 56 bytes, header [00 00 00 32 00 11] payload \"DIAGADR10BMWMAC001A37******BMWVINWBA********000001\""))
        assertTrue(log, log.contains("vehicle announcement (VIN/EID not shown)"))
        val vinHex = Sim.VIN.toByteArray().joinToString(" ") { "%02X".format(it) }
        assertFalse("VIN must not appear in plain text", log.contains(Sim.VIN))
        assertFalse("VIN must not appear as hex", log.contains(vinHex.substring(0, 24)))
        assertFalse(tester.log.export().contains(Sim.VIN))
    }

    @Test fun `unknown packets are logged as bounded hex`() {
        val d = PacketFormat.describe(9999, ByteArray(100) { it.toByte() })
        assertTrue(d, d.startsWith("100 bytes [00 01 02"))
        assertTrue(d, d.endsWith("2F …]"))
    }

    @Test fun `network errors are logged with their type and message`() = runTest {
        val (fake, tester) = setup()
        fake.udpSendFails = true
        val r = tester.discover(Sim.LINK, DiscoveryOptions(includeDoip = false)) as DiscoveryResult.Completed
        assertEquals(0, r.probesSent)
        assertTrue(tester.logText(), tester.logText().contains("Network error sending HSFZ vehicle identification from 169.254.10.2:50123 to 169.254.255.255:6811 via eth0: NoRouteToHostException: No route to host"))

        val (fake2, tester2) = setup()
        fake2.udpReceiveFails = true
        tester2.discover(Sim.LINK, DiscoveryOptions(includeDoip = false))
        assertTrue(tester2.logText().contains("Network error while receiving on 169.254.10.2:50123: SocketException: Network is unreachable"))

        val (fake3, tester3) = setup()
        fake3.udpOpenFails = true
        tester3.discover(Sim.LINK)
        assertTrue(tester3.logText().contains("Network error: could not open/bind a UDP socket to 169.254.10.2 on eth0: IOException: interface down"))
    }

    // ---- Binding verification ----------------------------------------------------------------------

    @Test fun `a socket bound to the wrong address or without broadcast is flagged`() = runTest {
        val (fake, tester) = setup()
        val ok = tester.discover(Sim.LINK) as DiscoveryResult.Completed
        assertTrue(ok.bindingWarnings.isEmpty())

        val (fake2, tester2) = setup()
        fake2.udpBoundTo = Ipv4Address.parse("192.168.1.50")
        fake2.udpBroadcast = false
        val bad = tester2.discover(Sim.LINK) as DiscoveryResult.Completed
        assertEquals(2, bad.bindingWarnings.size)
        assertTrue(tester2.logText().contains("WARNING: Socket is bound to 192.168.1.50, not 169.254.10.2 (eth0)"))
        assertTrue(tester2.logText().contains("WARNING: Broadcast (SO_BROADCAST) is not enabled"))
        assertTrue(fake.udpOpened == 1)
    }

    @Test fun `real JVM socket reports its bound address and broadcast flag`() = runBlocking {
        val loopback = Ipv4Address.parse("127.0.0.1")!!
        JvmEnetTransport().openUdp(loopback).use { udp ->
            assertEquals(loopback, udp.info.localAddress)
            assertTrue(udp.info.localPort > 0)
            assertEquals(true, udp.info.broadcastEnabled)
        }
    }

    // ---- Standalone TCP 6801 port check --------------------------------------------------------------

    @Test fun `port check reports open with latency and sends nothing`() = runTest {
        val (fake, tester) = setup()
        fake.tcpConnect[Hsfz.DIAG_PORT] = FakeTransport.Connect.OK to 23L
        val r = tester.checkTcpPort(Sim.LINK, Sim.CAR) as PortCheckResult.Open
        assertEquals(6801, r.port)
        assertEquals(23L, r.latencyMillis)
        assertTrue("no bytes are written", fake.tcpWritten.isEmpty())
        assertEquals(1, fake.tcpClosed)
        assertTrue(tester.logText().contains("TCP port check: 169.254.42.17:6801 OPEN in 23 ms (from 169.254.10.2:40001; simulated network binding (eth0)); closed without sending data"))
    }

    @Test fun `port check uses a 5 second connect timeout and reports refusal`() = runTest {
        val (fake, tester) = setup()
        fake.tcpConnect[Hsfz.DIAG_PORT] = FakeTransport.Connect.TIMEOUT to 0L
        val start = currentTime
        val timeout = tester.checkTcpPort(Sim.LINK, Sim.CAR) as PortCheckResult.Closed
        assertTrue(timeout.timedOut)
        assertEquals(5_000L, currentTime - start)

        fake.tcpConnect[Hsfz.DIAG_PORT] = FakeTransport.Connect.REFUSED to 1L
        val refused = tester.checkTcpPort(Sim.LINK, Sim.CAR) as PortCheckResult.Closed
        assertFalse(refused.timedOut)
        assertTrue(tester.logText().contains("Network error: TCP port check 169.254.42.17:6801 failed: ConnectException: Connection refused"))
    }

    @Test fun `port check refuses targets outside the car network without connecting`() = runTest {
        val (fake, tester) = setup()
        for (t in listOf("8.8.8.8", "127.0.0.1", "169.254.255.255")) {
            assertTrue(t, tester.checkTcpPort(Sim.LINK, Ipv4Address.parse(t)!!) is PortCheckResult.Refused)
        }
        assertTrue(fake.tcpConnectAttempts.isEmpty())
    }

    @Test fun `real JVM port check against a local listener`() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val loopback = Ipv4Address.parse("127.0.0.1")!!
            val tcp = JvmEnetTransport().connectTcp(loopback, loopback, server.localPort, 5_000)
            assertEquals(loopback, tcp.info.localAddress)
            tcp.close()
        }
    }
}
