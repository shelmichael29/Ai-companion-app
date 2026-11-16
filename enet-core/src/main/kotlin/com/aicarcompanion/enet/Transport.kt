package com.aicarcompanion.enet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

class Datagram(val from: Ipv4Address, val fromPort: Int, val data: ByteArray)

/** Where a socket actually ended up: logged so binding and broadcast can be verified on the phone. */
data class SocketInfo(
    /** Local IPv4 address the socket is bound to (null if not IPv4/unknown). */
    val localAddress: Ipv4Address?,
    val localPort: Int,
    /** SO_BROADCAST for UDP sockets; null for TCP. */
    val broadcastEnabled: Boolean?,
    /** How the socket was tied to a network, e.g. "Android Network 105 (eth0)". */
    val binding: String,
) {
    val endpoint: String get() = "${localAddress ?: "?"}:$localPort"
}

/** A UDP socket bound to the selected interface. */
interface UdpChannel : Closeable {
    val info: SocketInfo

    suspend fun send(to: Ipv4Address, port: Int, data: ByteArray)

    /** Next datagram, or null when [timeoutMillis] passes without one. */
    suspend fun receive(timeoutMillis: Long): Datagram?
}

/** A TCP connection opened from the selected interface. */
interface TcpChannel : Closeable {
    val info: SocketInfo
    suspend fun write(data: ByteArray)

    /** Up to [max] bytes; null on timeout; an empty array when the peer closed the connection. */
    suspend fun read(max: Int, timeoutMillis: Long): ByteArray?
}

/**
 * Opens sockets on one specific network interface. The Android app implements the binding with
 * `Network.bindSocket` so traffic can't leave through Wi-Fi or mobile data.
 */
interface EnetTransport {
    suspend fun openUdp(local: Ipv4Address): UdpChannel

    /** @throws IOException (refused, unreachable) or [SocketTimeoutException]. */
    suspend fun connectTcp(local: Ipv4Address, target: Ipv4Address, port: Int, timeoutMillis: Long): TcpChannel
}

/** Lets a platform attach sockets to a specific network before they are used. */
interface SocketBinder {
    fun bind(socket: DatagramSocket) {}
    fun bind(socket: Socket) {}

    /** Human-readable description of the binding, for the diagnostic log. */
    fun describe(): String = "bound to local address only (no platform network binding)"
}

/** java.net implementation, sockets bound to the local interface address (and optionally a platform network). */
class JvmEnetTransport(private val binder: SocketBinder = object : SocketBinder {}) : EnetTransport {

    override suspend fun openUdp(local: Ipv4Address): UdpChannel = withContext(Dispatchers.IO) {
        val socket = DatagramSocket(null)
        try {
            binder.bind(socket)
            socket.broadcast = true
            socket.bind(InetSocketAddress(local.toInet(), 0))
        } catch (e: IOException) {
            socket.close(); throw e
        }
        val udpInfo = SocketInfo(
            (socket.localAddress as? Inet4Address)?.let { Ipv4Address.of(it.address) },
            socket.localPort, socket.broadcast, binder.describe(),
        )
        object : UdpChannel {
            override val info = udpInfo

            override suspend fun send(to: Ipv4Address, port: Int, data: ByteArray) = withContext(Dispatchers.IO) {
                socket.send(DatagramPacket(data, data.size, to.toInet(), port))
            }

            override suspend fun receive(timeoutMillis: Long): Datagram? = withContext(Dispatchers.IO) {
                val buf = ByteArray(1500)
                val packet = DatagramPacket(buf, buf.size)
                socket.soTimeout = timeoutMillis.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    return@withContext null
                }
                val from = (packet.address as? Inet4Address)?.let { Ipv4Address.of(it.address) } ?: return@withContext null
                Datagram(from, packet.port, buf.copyOf(packet.length))
            }

            override fun close() = socket.close()
        }
    }

    override suspend fun connectTcp(local: Ipv4Address, target: Ipv4Address, port: Int, timeoutMillis: Long): TcpChannel =
        withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                binder.bind(socket)
                socket.tcpNoDelay = true
                socket.bind(InetSocketAddress(local.toInet(), 0))
                socket.connect(InetSocketAddress(target.toInet(), port), timeoutMillis.toInt())
            } catch (e: IOException) {
                socket.close(); throw e
            }
            val tcpInfo = SocketInfo(
                (socket.localAddress as? Inet4Address)?.let { Ipv4Address.of(it.address) },
                socket.localPort, null, binder.describe(),
            )
            object : TcpChannel {
                override val info = tcpInfo

                override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
                    socket.getOutputStream().apply { write(data); flush() }
                    Unit
                }

                override suspend fun read(max: Int, timeoutMillis: Long): ByteArray? = withContext(Dispatchers.IO) {
                    socket.soTimeout = timeoutMillis.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                    val buf = ByteArray(max)
                    val n = try { socket.getInputStream().read(buf) } catch (e: SocketTimeoutException) { return@withContext null }
                    if (n < 0) ByteArray(0) else buf.copyOf(n)
                }

                override fun close() = socket.close()
            }
        }

    private fun Ipv4Address.toInet(): InetAddress = InetAddress.getByAddress(toBytes())
}
