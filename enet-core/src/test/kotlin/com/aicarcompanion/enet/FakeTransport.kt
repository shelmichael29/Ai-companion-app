package com.aicarcompanion.enet

import kotlinx.coroutines.delay
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * Simulated network driven by the test scheduler's virtual clock. Replies are scripted, so
 * every test is deterministic and nothing touches a real network.
 */
class FakeTransport(private val now: () -> Long) : EnetTransport {

    data class Sent(val to: Ipv4Address, val port: Int, val data: ByteArray)

    /** (destination, port) of a probe → replies as (delay ms, datagram). */
    val udpReplies = mutableMapOf<Pair<Ipv4Address, Int>, List<Pair<Long, Datagram>>>()
    val udpSent = mutableListOf<Sent>()
    var udpOpenFails = false
    var udpOpened = 0
    /** Overrides what the "OS" reports for the UDP socket (to simulate a wrong binding). */
    var udpBoundTo: Ipv4Address? = null
    var udpBroadcast: Boolean = true
    var udpSendFails = false
    var udpReceiveFails = false

    enum class Connect { OK, REFUSED, TIMEOUT }
    /** port → how connecting behaves, and how long it takes. */
    val tcpConnect = mutableMapOf<Int, Pair<Connect, Long>>()
    /** port → (bytes written by the tester) → replies as (delay ms, bytes). */
    val tcpResponder = mutableMapOf<Int, (ByteArray) -> List<Pair<Long, ByteArray>>>()
    val tcpWritten = mutableListOf<Pair<Int, ByteArray>>()
    val tcpConnectAttempts = mutableListOf<Pair<Ipv4Address, Int>>()
    var tcpClosed = 0
    /** Simulates the cable being pulled: after this many writes, writing fails like a reset connection. */
    var tcpDropAfterWrites: Int? = null

    private class Pending(val at: Long, val payload: Any)

    override suspend fun openUdp(local: Ipv4Address): UdpChannel {
        if (udpOpenFails) throw IOException("interface down")
        udpOpened++
        val inbox = mutableListOf<Pending>()
        return object : UdpChannel {
            override val info = SocketInfo(udpBoundTo ?: local, 50123, udpBroadcast, "simulated network binding (eth0)")

            override suspend fun send(to: Ipv4Address, port: Int, data: ByteArray) {
                if (udpSendFails) throw java.net.NoRouteToHostException("No route to host")
                udpSent += Sent(to, port, data)
                udpReplies[to to port].orEmpty().forEach { (d, dg) -> inbox += Pending(now() + d, dg) }
            }

            override suspend fun receive(timeoutMillis: Long): Datagram? {
                if (udpReceiveFails) throw java.net.SocketException("Network is unreachable")
                val next = inbox.minByOrNull { it.at }
                if (next == null || next.at > now() + timeoutMillis) {
                    delay(timeoutMillis); return null
                }
                delay((next.at - now()).coerceAtLeast(0)); inbox.remove(next)
                return next.payload as Datagram
            }

            override fun close() {}
        }
    }

    override suspend fun connectTcp(local: Ipv4Address, target: Ipv4Address, port: Int, timeoutMillis: Long): TcpChannel {
        tcpConnectAttempts += target to port
        val (behavior, takes) = tcpConnect[port] ?: (Connect.REFUSED to 1L)
        when (behavior) {
            Connect.REFUSED -> { delay(takes); throw ConnectException("Connection refused") }
            Connect.TIMEOUT -> { delay(timeoutMillis); throw SocketTimeoutException("connect timed out") }
            Connect.OK -> delay(takes)
        }
        val inbox = mutableListOf<Pending>()
        return object : TcpChannel {
            override val info = SocketInfo(local, 40001, null, "simulated network binding (eth0)")

            override suspend fun write(data: ByteArray) {
                val limit = tcpDropAfterWrites
                if (limit != null && tcpWritten.count { it.first == port } >= limit) throw java.net.SocketException("Connection reset")
                tcpWritten += port to data
                tcpResponder[port]?.invoke(data).orEmpty().forEach { (d, b) -> inbox += Pending(now() + d, b) }
            }

            override suspend fun read(max: Int, timeoutMillis: Long): ByteArray? {
                val next = inbox.minByOrNull { it.at }
                if (next == null || next.at > now() + timeoutMillis) {
                    delay(timeoutMillis); return null
                }
                delay((next.at - now()).coerceAtLeast(0)); inbox.remove(next)
                return next.payload as ByteArray
            }

            override fun close() { tcpClosed++ }
        }
    }
}

/** Builds realistic gateway replies. */
object Sim {
    val CAR = Ipv4Address.parse("169.254.42.17")!!
    val PHONE = Ipv4Address.parse("169.254.10.2")!!
    val LINK = EnetLink("eth0", Ipv4Subnet(PHONE, 16))
    const val VIN = "WBAFR1C51BC000001"

    fun hsfzIdentReply(from: Ipv4Address = CAR, vin: String = VIN, mac: String = "001A37A1B2C3") =
        Datagram(from, Hsfz.IDENT_PORT, Hsfz.frame(Hsfz.CTRL_IDENT, "DIAGADR10BMWMAC${mac}BMWVIN$vin".toByteArray()))

    fun doipAnnouncement(from: Ipv4Address = CAR, vin: String = VIN, addr: Int = 0x0010): Datagram {
        val payload = vin.toByteArray() + byteArrayOf((addr ushr 8).toByte(), addr.toByte()) +
            byteArrayOf(0, 0x1A, 0x37, 1, 2, 3) + ByteArray(6) + byteArrayOf(0x00)
        return Datagram(from, Doip.PORT, Doip.header(Doip.TYPE_VEHICLE_ANNOUNCEMENT, payload.size) + payload)
    }

    fun diag(src: Int, dst: Int, vararg uds: Int) = Hsfz.frame(Hsfz.CTRL_DIAG, byteArrayOf(src.toByte(), dst.toByte()) + uds.map { it.toByte() })

    fun ack(request: ByteArray) = Hsfz.frame(Hsfz.CTRL_ACK, request.copyOfRange(Hsfz.HEADER_SIZE, request.size))
}
