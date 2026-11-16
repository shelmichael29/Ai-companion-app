package com.aicarcompanion.enet

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Exercises the real java.net transport against a simulated HSFZ gateway on loopback.
 * (The tester itself refuses loopback targets; this only checks the socket code.)
 */
class JvmTransportLoopbackTest {
    private val loopback = Ipv4Address.parse("127.0.0.1")!!
    private val transport = JvmEnetTransport()

    @Test fun `tcp session against a simulated gateway`() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val gateway = thread {
                server.accept().use { s ->
                    val req = ByteArray(10)
                    var n = 0
                    while (n < req.size) n += s.getInputStream().read(req, n, req.size - n)
                    val out = s.getOutputStream()
                    out.write(Hsfz.frame(Hsfz.CTRL_ACK, req.copyOfRange(6, 10)))
                    out.write(Sim.diag(0x10, 0xF4, 0x7E, 0x00))
                    out.flush()
                    Thread.sleep(200)
                }
            }
            val tcp = transport.connectTcp(loopback, loopback, server.localPort, 2_000)
            tcp.use {
                it.write(Hsfz.diagnosticFrame(SafeRequest.TESTER_PRESENT))
                val reader = HsfzFrameReader()
                val frames = mutableListOf<HsfzFrame>()
                while (frames.size < 2) frames += reader.feed(it.read(1024, 2_000) ?: break)
                assertEquals(listOf(Hsfz.CTRL_ACK, Hsfz.CTRL_DIAG), frames.map { f -> f.control })
                assertArrayEquals(byteArrayOf(0x10, 0xF4.toByte(), 0x7E, 0x00), frames[1].payload)
            }
            gateway.join(2_000)
        }
    }

    @Test fun `tcp read times out and refused connections throw`() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val t = thread { runCatching { server.accept().use { Thread.sleep(500) } } }
            transport.connectTcp(loopback, loopback, server.localPort, 2_000).use { assertNull(it.read(16, 100)) }
            t.join(2_000)
        }
        val freePort = ServerSocket(0).use { it.localPort }
        val error = runCatching { transport.connectTcp(loopback, loopback, freePort, 1_000) }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
    }

    @Test fun `udp identification round trip`() = runBlocking {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { gw ->
            val responder = thread {
                val buf = ByteArray(64)
                val p = DatagramPacket(buf, buf.size)
                gw.receive(p)
                val reply = Sim.hsfzIdentReply().data
                gw.send(DatagramPacket(reply, reply.size, p.address, p.port))
            }
            transport.openUdp(loopback).use { udp ->
                udp.send(loopback, gw.localPort, Hsfz.IDENT_REQUEST)
                val d = udp.receive(2_000)!!
                assertEquals(Sim.VIN, HsfzIdentParser.parse(d.data)?.vin)
                assertNull(udp.receive(100))
            }
            responder.join(2_000)
        }
    }
}
