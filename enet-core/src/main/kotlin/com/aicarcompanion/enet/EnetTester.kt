package com.aicarcompanion.enet

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import java.io.IOException
import java.net.SocketTimeoutException

/** The user-selected Ethernet link: interface name plus the phone's address on it. */
data class EnetLink(val interfaceName: String, val subnet: Ipv4Subnet)

enum class GatewayProtocol(val label: String) { HSFZ("BMW HSFZ (ENET)"), DOIP("ISO 13400 DoIP") }

data class DiscoveredGateway(
    val address: Ipv4Address,
    val protocol: GatewayProtocol,
    val latencyMillis: Long,
    /** HSFZ diagnostic address or DoIP logical address, as reported. */
    val diagAddress: String?,
    /** Masked; never the full VIN. */
    val maskedVin: String?,
    /** Masked; manufacturer prefix only. */
    val maskedMac: String?,
)

sealed interface DiscoveryResult {
    data class Completed(
        val gateways: List<DiscoveredGateway>,
        val probesSent: Int,
        val ignoredPackets: Int,
        /** Where the UDP socket was bound, as reported by the OS. */
        val socket: SocketInfo? = null,
        /** Problems found when checking the socket against the selected interface (empty = verified). */
        val bindingWarnings: List<String> = emptyList(),
        val listenMillis: Long = DiscoveryOptions.DEFAULT_LISTEN_MILLIS,
    ) : DiscoveryResult
    data class Refused(val reason: String) : DiscoveryResult
}

enum class StepOutcome { PASS, FAIL, NOT_SUPPORTED, SKIPPED }

data class StepResult(val name: String, val outcome: StepOutcome, val detail: String, val latencyMillis: Long? = null)

sealed interface ConnectionResult {
    data class Completed(val target: Ipv4Address, val steps: List<StepResult>) : ConnectionResult {
        val transportOk: Boolean get() = steps.firstOrNull()?.outcome == StepOutcome.PASS
        val sessionOk: Boolean get() = steps.any { it.name == STEP_SESSION && it.outcome == StepOutcome.PASS }
    }
    data class Refused(val reason: String) : ConnectionResult

    companion object {
        const val STEP_TCP = "TCP connection to port 6801"
        const val STEP_SESSION = "Diagnostic session check (TesterPresent to gateway)"
        const val STEP_IGNITION = "Ignition status (control port 6811)"
        const val STEP_DOIP = "DoIP TCP port 13400"
    }
}

data class DiscoveryOptions(
    val manualTarget: Ipv4Address? = null,
    val includeDoip: Boolean = true,
    /** How long to wait for HSFZ/DoIP answers after sending. Clamped to [LISTEN_RANGE]. */
    val listenMillis: Long = DEFAULT_LISTEN_MILLIS,
    val maxResponses: Int = 16,
) {
    companion object {
        const val DEFAULT_LISTEN_MILLIS = 5_000L
        val LISTEN_RANGE = 1_000L..15_000L
    }
}

data class ConnectionOptions(
    val sendTesterPresent: Boolean = true,
    val queryIgnition: Boolean = false,
    val checkDoipPort: Boolean = false,
    val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    val responseTimeoutMillis: Long = 3_000,
) {
    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000L
        val CONNECT_TIMEOUT_RANGE = 1_000L..15_000L
    }
}

/** Result of the standalone, non-invasive TCP port check (connect, then close; no bytes sent). */
sealed interface PortCheckResult {
    data class Open(val target: Ipv4Address, val port: Int, val latencyMillis: Long, val local: SocketInfo) : PortCheckResult
    data class Closed(val target: Ipv4Address, val port: Int, val reason: String, val timedOut: Boolean) : PortCheckResult
    data class Refused(val reason: String) : PortCheckResult
}

/**
 * Discovery and connection checks for BMW ENET gateways. Everything here is bounded (fixed
 * number of probes, fixed timeouts, one operation at a time) and limited by [TargetPolicy] to
 * the selected link. Each method runs only when called, i.e. after an explicit user action.
 */
class EnetTester(
    private val transport: EnetTransport,
    private val clock: () -> Long,
    val log: EnetLog = EnetLog(clock),
) {
    private val busy = Mutex()

    suspend fun discover(link: EnetLink, options: DiscoveryOptions = DiscoveryOptions()): DiscoveryResult {
        (TargetPolicy.checkLocal(link.subnet) as? TargetPolicy.Verdict.Rejected)?.let { return refused(it.reason) }
        options.manualTarget?.let { t ->
            (TargetPolicy.checkTarget(t, link.subnet) as? TargetPolicy.Verdict.Rejected)?.let { return refused(it.reason) }
        }
        if (!busy.tryLock()) return DiscoveryResult.Refused("Another test is already running.")
        try {
            return runDiscovery(link, options)
        } finally {
            busy.unlock()
        }
    }

    private fun refused(reason: String): DiscoveryResult.Refused {
        log.add("Discovery refused: $reason")
        return DiscoveryResult.Refused(reason)
    }

    private suspend fun runDiscovery(link: EnetLink, o: DiscoveryOptions): DiscoveryResult {
        val listen = o.listenMillis.coerceIn(DiscoveryOptions.LISTEN_RANGE)
        val destinations = buildList {
            if (link.subnet.prefixLength < 31) add(link.subnet.broadcast)
            add(Ipv4Address.LIMITED_BROADCAST)
            o.manualTarget?.let(::add)
        }.distinct()
        log.add("Discovery on ${link.interfaceName} (${link.subnet}); destinations: ${destinations.joinToString()}; listen window $listen ms")
        val channel = try {
            transport.openUdp(link.subnet.address)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.add("Network error: could not open/bind a UDP socket to ${link.subnet.address} on ${link.interfaceName}: ${e.describe()}")
            return DiscoveryResult.Refused("Couldn't open the Ethernet interface: ${e.message ?: e.javaClass.simpleName}")
        }
        val found = linkedMapOf<Pair<Ipv4Address, GatewayProtocol>, DiscoveredGateway>()
        var sent = 0
        var ignored = 0
        val info = channel.info
        val warnings = verifyUdpSocket(info, link)
        log.add("UDP socket bound to ${info.endpoint} on ${link.interfaceName}; ${info.binding}; SO_BROADCAST=${info.broadcastEnabled}")
        warnings.forEach { log.add("WARNING: $it") }
        channel.use { udp ->
            val start = clock()
            for (dest in destinations) {
                sent += send(udp, link, dest, Hsfz.IDENT_PORT, Hsfz.IDENT_REQUEST, "HSFZ vehicle identification")
                if (o.includeDoip) sent += send(udp, link, dest, Doip.PORT, Doip.VEHICLE_ID_REQUEST, "DoIP vehicle identification")
            }
            val deadline = start + listen
            while (found.size < o.maxResponses) {
                val remaining = deadline - clock()
                if (remaining <= 0) break
                val d = try {
                    udp.receive(remaining)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.add("Network error while receiving on ${info.endpoint}: ${e.describe()}")
                    break
                } ?: break
                val latency = clock() - start
                log.add("← UDP ${d.from}:${d.fromPort} → ${info.endpoint} after $latency ms: ${PacketFormat.describe(d.fromPort, d.data)}")
                val allowed = TargetPolicy.checkTarget(d.from, link.subnet) == TargetPolicy.Verdict.Allowed
                if (!allowed) {
                    ignored++; log.add("Ignored reply from ${d.from} (outside the car network)"); continue
                }
                val gw = when (d.fromPort) {
                    Hsfz.IDENT_PORT -> HsfzIdentParser.parse(d.data)?.let {
                        DiscoveredGateway(d.from, GatewayProtocol.HSFZ, latency, it.diagAddress, it.vin?.let(Redaction::maskVin), it.mac?.let(Redaction::maskMac))
                    }
                    Doip.PORT -> DoipParser.parseAnnouncement(d.data)?.let {
                        DiscoveredGateway(d.from, GatewayProtocol.DOIP, latency, "0x%04X".format(it.logicalAddress), Redaction.maskVin(it.vin), Redaction.maskMac(it.eid))
                    }
                    else -> null
                }
                if (gw == null) {
                    ignored++; log.add("Ignored ${d.data.size}-byte reply from ${d.from}:${d.fromPort} (not a gateway identification)"); continue
                }
                if (found.putIfAbsent(gw.address to gw.protocol, gw) == null) {
                    log.add("${gw.protocol.label} gateway at ${gw.address} in ${gw.latencyMillis} ms (diag address ${gw.diagAddress}, VIN ${gw.maskedVin ?: "not reported"})")
                }
            }
        }
        if (found.isEmpty()) log.add("No gateway answered within $listen ms.")
        if (o.includeDoip && found.values.none { it.protocol == GatewayProtocol.DOIP }) {
            log.add("No DoIP answer (expected on 2008–2017 F-series cars, which use HSFZ).")
        }
        return DiscoveryResult.Completed(found.values.toList(), sent, ignored, info, warnings, listen)
    }

    /** Sends one datagram and logs exactly what left which socket. Returns 1 if sent, 0 on error. */
    private suspend fun send(udp: UdpChannel, link: EnetLink, dest: Ipv4Address, port: Int, bytes: ByteArray, what: String): Int = try {
        udp.send(dest, port, bytes)
        log.add("→ UDP ${udp.info.endpoint} → $dest:$port via ${link.interfaceName}: ${bytes.size} bytes [${bytes.hex()}] ($what)")
        1
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.add("Network error sending $what from ${udp.info.endpoint} to $dest:$port via ${link.interfaceName}: ${e.describe()}")
        0
    }

    /**
     * Checks that the OS put the socket where we asked: on the selected interface's address and
     * with broadcast enabled. Problems are reported, not hidden.
     */
    private fun verifyUdpSocket(info: SocketInfo, link: EnetLink): List<String> = buildList {
        when (info.localAddress) {
            null -> add("The socket's local address is unknown; can't confirm it uses ${link.interfaceName}.")
            link.subnet.address -> Unit
            else -> add("Socket is bound to ${info.localAddress}, not ${link.subnet.address} (${link.interfaceName}); probes may leave through another network.")
        }
        if (info.broadcastEnabled != true) add("Broadcast (SO_BROADCAST) is not enabled on the socket; broadcast discovery can't work.")
    }

    /**
     * Non-invasive TCP check: connect to [port] on a manually entered gateway, measure the
     * connect time, close. No bytes are written.
     */
    suspend fun checkTcpPort(
        link: EnetLink,
        target: Ipv4Address,
        port: Int = Hsfz.DIAG_PORT,
        timeoutMillis: Long = ConnectionOptions.DEFAULT_CONNECT_TIMEOUT_MILLIS,
    ): PortCheckResult {
        (TargetPolicy.checkLocal(link.subnet) as? TargetPolicy.Verdict.Rejected)?.let { log.add("Port check refused: ${it.reason}"); return PortCheckResult.Refused(it.reason) }
        (TargetPolicy.checkTarget(target, link.subnet) as? TargetPolicy.Verdict.Rejected)?.let { log.add("Port check refused: ${it.reason}"); return PortCheckResult.Refused(it.reason) }
        if (!busy.tryLock()) return PortCheckResult.Refused("Another test is already running.")
        try {
            val timeout = timeoutMillis.coerceIn(ConnectionOptions.CONNECT_TIMEOUT_RANGE)
            log.add("TCP port check: ${link.subnet.address} (${link.interfaceName}) → $target:$port, timeout $timeout ms, no data will be sent")
            val t0 = clock()
            return try {
                val tcp = transport.connectTcp(link.subnet.address, target, port, timeout)
                val ms = clock() - t0
                val info = tcp.info
                tcp.close()
                log.add("TCP port check: $target:$port OPEN in $ms ms (from ${info.endpoint}; ${info.binding}); closed without sending data")
                PortCheckResult.Open(target, port, ms, info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SocketTimeoutException) {
                log.add("TCP port check: $target:$port no answer within $timeout ms (${e.describe()})")
                PortCheckResult.Closed(target, port, "No answer within $timeout ms (cable, ignition or wrong address?).", timedOut = true)
            } catch (e: IOException) {
                log.add("Network error: TCP port check $target:$port failed: ${e.describe()}")
                PortCheckResult.Closed(target, port, "Connection failed: ${e.message ?: e.javaClass.simpleName}", timedOut = false)
            }
        } finally {
            busy.unlock()
        }
    }

    suspend fun testConnection(link: EnetLink, target: Ipv4Address, options: ConnectionOptions = ConnectionOptions()): ConnectionResult {
        (TargetPolicy.checkLocal(link.subnet) as? TargetPolicy.Verdict.Rejected)?.let { log.add("Test refused: ${it.reason}"); return ConnectionResult.Refused(it.reason) }
        (TargetPolicy.checkTarget(target, link.subnet) as? TargetPolicy.Verdict.Rejected)?.let { log.add("Test refused: ${it.reason}"); return ConnectionResult.Refused(it.reason) }
        if (!busy.tryLock()) return ConnectionResult.Refused("Another test is already running.")
        try {
            log.add("Connection test to $target from ${link.interfaceName} (${link.subnet.address})")
            val steps = mutableListOf<StepResult>()
            steps += hsfzSession(link, target, options)
            steps += if (options.queryIgnition && steps.first().outcome == StepOutcome.PASS) ignition(link, target, options)
            else StepResult(ConnectionResult.STEP_IGNITION, StepOutcome.SKIPPED, if (options.queryIgnition) "Skipped: no TCP connection." else "Not selected.")
            steps += if (options.checkDoipPort) doipPort(link, target, options) else StepResult(ConnectionResult.STEP_DOIP, StepOutcome.SKIPPED, "Not selected.")
            return ConnectionResult.Completed(target, steps)
        } finally {
            busy.unlock()
        }
    }

    /** TCP connect to 6801, then (optionally) one TesterPresent to the gateway, then close. */
    private suspend fun hsfzSession(link: EnetLink, target: Ipv4Address, o: ConnectionOptions): List<StepResult> {
        val t0 = clock()
        val tcp = try {
            transport.connectTcp(link.subnet.address, target, Hsfz.DIAG_PORT, o.connectTimeoutMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            log.add("TCP $target:${Hsfz.DIAG_PORT}: no answer within ${o.connectTimeoutMillis} ms")
            return listOf(
                StepResult(ConnectionResult.STEP_TCP, StepOutcome.FAIL, "No answer within ${o.connectTimeoutMillis} ms. Check the cable, ignition and target address."),
                StepResult(ConnectionResult.STEP_SESSION, StepOutcome.SKIPPED, "Skipped: no TCP connection."),
            )
        } catch (e: IOException) {
            log.add("Network error: TCP $target:${Hsfz.DIAG_PORT}: ${e.describe()}")
            return listOf(
                StepResult(ConnectionResult.STEP_TCP, StepOutcome.FAIL, "Connection failed: ${e.message ?: e.javaClass.simpleName}"),
                StepResult(ConnectionResult.STEP_SESSION, StepOutcome.SKIPPED, "Skipped: no TCP connection."),
            )
        }
        val connectMs = clock() - t0
        log.add("TCP ${tcp.info.endpoint} → $target:${Hsfz.DIAG_PORT} connected in $connectMs ms (${tcp.info.binding})")
        val tcpStep = StepResult(ConnectionResult.STEP_TCP, StepOutcome.PASS, "Connected.", connectMs)
        if (!o.sendTesterPresent) {
            tcp.close()
            return listOf(tcpStep, StepResult(ConnectionResult.STEP_SESSION, StepOutcome.SKIPPED, "Not selected."))
        }
        val session = tcp.use { testerPresent(it, o) }
        return listOf(tcpStep, session)
    }

    private suspend fun testerPresent(tcp: TcpChannel, o: ConnectionOptions): StepResult {
        val request = SafeRequest.TESTER_PRESENT
        val frame = Hsfz.diagnosticFrame(request)
        val sentPayload = frame.copyOfRange(Hsfz.HEADER_SIZE, frame.size)
        val reader = HsfzFrameReader()
        val t0 = clock()
        try {
            tcp.write(frame)
        } catch (e: IOException) {
            return StepResult(ConnectionResult.STEP_SESSION, StepOutcome.FAIL, "Sending failed: ${e.message}")
        }
        log.add("→ TCP ${tcp.info.endpoint} → port ${Hsfz.DIAG_PORT}: ${frame.size} bytes [${frame.hex()}] (${request.label} to gateway 0x%02X)".format(Hsfz.GATEWAY_ADDRESS))
        var acked = false
        val deadline = t0 + o.responseTimeoutMillis
        while (true) {
            val remaining = deadline - clock()
            if (remaining <= 0) break
            val bytes = try { tcp.read(1024, remaining) } catch (e: IOException) {
                return StepResult(ConnectionResult.STEP_SESSION, StepOutcome.FAIL, "Connection dropped: ${e.message}")
            } ?: break
            if (bytes.isEmpty()) return StepResult(ConnectionResult.STEP_SESSION, StepOutcome.FAIL, "The gateway closed the connection.")
            val frames = try { reader.feed(bytes) } catch (e: HsfzProtocolException) {
                log.add("Protocol error: ${e.message}")
                return StepResult(ConnectionResult.STEP_SESSION, StepOutcome.FAIL, "Unexpected data: ${e.message}")
            }
            for (f in frames) {
                when (f.control) {
                    Hsfz.CTRL_ALIVE_CHECK -> {
                        log.add("← Alive check from gateway; answering")
                        runCatching { tcp.write(Hsfz.ALIVE_RESPONSE) }
                    }
                    Hsfz.CTRL_ACK -> {
                        val matches = f.payload.isNotEmpty() && sentPayload.copyOf(minOf(sentPayload.size, f.payload.size)).contentEquals(f.payload)
                        log.add("← Acknowledgement (${if (matches) "matches request" else "does not match request"})")
                        if (matches) acked = true
                    }
                    Hsfz.CTRL_NACK -> {
                        log.add("← Negative acknowledgement")
                        return StepResult(ConnectionResult.STEP_SESSION, StepOutcome.FAIL, "The gateway rejected the message (HSFZ NACK).", clock() - t0)
                    }
                    Hsfz.CTRL_DIAG -> {
                        val p = f.payload
                        if (p.size < 3) continue
                        val src = p[0].toInt() and 0xFF
                        val dst = p[1].toInt() and 0xFF
                        val uds = p.copyOfRange(2, p.size)
                        if (src != Hsfz.GATEWAY_ADDRESS || dst != Hsfz.TESTER_ADDRESS) {
                            log.add("← Diagnostic message from 0x%02X to 0x%02X ignored".format(src, dst)); continue
                        }
                        val ms = clock() - t0
                        return when {
                            uds.contentEquals(request.positiveResponse) -> {
                                log.add("← Positive response ${uds.hex()} in $ms ms")
                                StepResult(ConnectionResult.STEP_SESSION, StepOutcome.PASS, "Gateway answered TesterPresent${if (acked) " (acknowledged)" else ""}.", ms)
                            }
                            uds.size >= 3 && uds[0] == 0x7F.toByte() && uds[1] == request.uds[0] -> {
                                log.add("← Negative response ${uds.hex()} in $ms ms")
                                StepResult(ConnectionResult.STEP_SESSION, StepOutcome.PASS, "Gateway reachable; it declined TesterPresent (NRC 0x%02X).".format(uds[2].toInt() and 0xFF), ms)
                            }
                            else -> {
                                log.add("← Unexpected response ${uds.hex()}")
                                StepResult(ConnectionResult.STEP_SESSION, StepOutcome.FAIL, "Unexpected response from the gateway: ${uds.hex()}.", ms)
                            }
                        }
                    }
                    else -> log.add("← Frame with control word 0x%04X ignored".format(f.control))
                }
            }
        }
        val detail = if (acked) "Acknowledged, but no diagnostic response within ${o.responseTimeoutMillis} ms." else "No answer within ${o.responseTimeoutMillis} ms."
        log.add("TesterPresent: $detail")
        return StepResult(ConnectionResult.STEP_SESSION, StepOutcome.FAIL, detail)
    }

    /** Read-only clamp/ignition status on the control port (as used by EdiabasLib). Experimental. */
    private suspend fun ignition(link: EnetLink, target: Ipv4Address, o: ConnectionOptions): StepResult {
        val t0 = clock()
        val tcp = try {
            transport.connectTcp(link.subnet.address, target, Hsfz.CONTROL_PORT, o.connectTimeoutMillis)
        } catch (e: CancellationException) { throw e } catch (e: IOException) {
            return StepResult(ConnectionResult.STEP_IGNITION, StepOutcome.NOT_SUPPORTED, "Control port not reachable: ${e.message ?: e.javaClass.simpleName}")
        }
        return tcp.use {
            it.write(Hsfz.IGNITION_REQUEST)
            log.add("→ Ignition status request to $target:${Hsfz.CONTROL_PORT}")
            val reader = HsfzFrameReader()
            val deadline = t0 + o.responseTimeoutMillis
            while (true) {
                val remaining = deadline - clock()
                if (remaining <= 0) break
                val bytes = it.read(256, remaining) ?: break
                if (bytes.isEmpty()) break
                val f = (try { reader.feed(bytes) } catch (e: HsfzProtocolException) { emptyList() }).firstOrNull { f -> f.control == Hsfz.CTRL_IGNITION } ?: continue
                val ms = clock() - t0
                val status = f.payload.firstOrNull()?.toInt()?.and(0xFF)
                    ?: return@use StepResult(ConnectionResult.STEP_IGNITION, StepOutcome.FAIL, "Empty status reply.", ms)
                val on = (status and 0x0C) == 0x04
                log.add("← Ignition status 0x%02X (%s)".format(status, if (on) "on" else "off"))
                return@use StepResult(ConnectionResult.STEP_IGNITION, StepOutcome.PASS, "Ignition ${if (on) "on" else "off"} (status 0x%02X).".format(status), ms)
            }
            StepResult(ConnectionResult.STEP_IGNITION, StepOutcome.NOT_SUPPORTED, "No status reply within ${o.responseTimeoutMillis} ms.")
        }
    }

    /** TCP connect only, to see whether a DoIP entity listens. No DoIP messages are sent. */
    private suspend fun doipPort(link: EnetLink, target: Ipv4Address, o: ConnectionOptions): StepResult {
        val t0 = clock()
        return try {
            transport.connectTcp(link.subnet.address, target, Doip.PORT, o.connectTimeoutMillis).close()
            val ms = clock() - t0
            log.add("TCP $target:${Doip.PORT} open ($ms ms)")
            StepResult(ConnectionResult.STEP_DOIP, StepOutcome.PASS, "Port open: this gateway may support DoIP.", ms)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            log.add("TCP $target:${Doip.PORT}: ${e.message ?: e.javaClass.simpleName}")
            StepResult(ConnectionResult.STEP_DOIP, StepOutcome.NOT_SUPPORTED, "Not available (expected on a 2011 F10, which uses HSFZ).")
        }
    }
}

private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

private fun Throwable.describe() = "${javaClass.simpleName}: ${message ?: "no details"}"
