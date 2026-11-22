package com.aicarcompanion.enettester

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aicarcompanion.enet.ConnectionOptions
import com.aicarcompanion.enet.ConnectionResult
import com.aicarcompanion.enet.DiscoveryOptions
import com.aicarcompanion.enet.DiscoveryResult
import com.aicarcompanion.enet.EnetLink
import com.aicarcompanion.enet.EnetLog
import com.aicarcompanion.enet.EnetTester
import com.aicarcompanion.enet.Ipv4Address
import com.aicarcompanion.enet.JvmEnetTransport
import com.aicarcompanion.enet.PortCheckResult
import com.aicarcompanion.enet.SocketBinder
import com.aicarcompanion.enet.TargetPolicy
import com.aicarcompanion.enet.F10Module
import com.aicarcompanion.enet.ModuleDiagnostics
import com.aicarcompanion.enet.ModuleReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.net.DatagramSocket
import java.net.Socket

/**
 * Nothing here touches the network on its own: discovery and connection tests run only from the
 * two buttons. Sockets are bound to the Ethernet network the user selected.
 */
class TesterViewModel(app: Application) : AndroidViewModel(app) {
    private val clock = { SystemClock.elapsedRealtime() }
    val log = EnetLog(clock)
    private var selectedNetwork: android.net.Network? = null

    private val tester = EnetTester(
        JvmEnetTransport(object : SocketBinder {
            override fun bind(socket: DatagramSocket) { selectedNetwork?.bindSocket(socket) ?: error("No Ethernet interface selected") }
            override fun bind(socket: Socket) { selectedNetwork?.bindSocket(socket) ?: error("No Ethernet interface selected") }
            override fun describe() = "Network.bindSocket to Android network $selectedNetwork ($selectedName)"
        }),
        clock,
        log,
    )

    val monitor = EthernetMonitor(app).also { it.start() }

    var selectedName by mutableStateOf<String?>(null)
    var targetSelectionNotice by mutableStateOf("No discovered gateway selected. Enter an address or tap Use as target.")
        private set
    var targetText by mutableStateOf("")
    var includeDoip by mutableStateOf(false)
    var sendTesterPresent by mutableStateOf(true)
    var queryIgnition by mutableStateOf(false)
    var checkDoipPort by mutableStateOf(false)
    private val prefs = app.getSharedPreferences("enet_tester", android.content.Context.MODE_PRIVATE)

    /** Advanced: how long discovery listens for HSFZ/DoIP answers (default 9 s). */
    var listenMillis by mutableLongStateOf(prefs.getLong(KEY_LISTEN, DiscoveryOptions.DEFAULT_LISTEN_MILLIS).coerceIn(DiscoveryOptions.LISTEN_RANGE))
        private set

    /** Advanced: TCP connect timeout for the port check and connection test (default 5 s). */
    var connectTimeoutMillis by mutableLongStateOf(
        prefs.getLong(KEY_CONNECT, ConnectionOptions.DEFAULT_CONNECT_TIMEOUT_MILLIS).coerceIn(ConnectionOptions.CONNECT_TIMEOUT_RANGE),
    )
        private set

    fun updateListenMillis(ms: Long) {
        listenMillis = ms.coerceIn(DiscoveryOptions.LISTEN_RANGE); prefs.edit().putLong(KEY_LISTEN, listenMillis).apply()
    }

    fun updateConnectTimeout(ms: Long) {
        connectTimeoutMillis = ms.coerceIn(ConnectionOptions.CONNECT_TIMEOUT_RANGE); prefs.edit().putLong(KEY_CONNECT, connectTimeoutMillis).apply()
    }

    fun restoreAdvancedDefaults() {
        updateListenMillis(DiscoveryOptions.DEFAULT_LISTEN_MILLIS); updateConnectTimeout(ConnectionOptions.DEFAULT_CONNECT_TIMEOUT_MILLIS)
    }

    var portCheck by mutableStateOf<PortCheckResult?>(null)
        private set

    var running by mutableStateOf<String?>(null)
        private set
    var discovery by mutableStateOf<DiscoveryResult?>(null)
        private set
    var connection by mutableStateOf<ConnectionResult?>(null)
        private set

    var moduleStatus by mutableStateOf("")
        private set
    var moduleRows by mutableStateOf(listOf<String>())
        private set
    var moduleActive by mutableStateOf(false)
        private set
    private var moduleJob: Job? = null
    private var moduleBinding: ModuleBinding? = null
    private var discoveryNetwork: android.net.Network? = null

    init {
        viewModelScope.launch {
            monitor.interfaces.collect { interfaces ->
                val binding = moduleBinding
                if (binding != null && interfaces[binding.network]?.ipv4 != binding.subnet) cancelModules("Ethernet disconnected")
                if (discoveryNetwork !in interfaces.keys) {
                    discoveryNetwork = null
                    discovery = null
                }
            }
        }
    }

    fun selected(): EthernetInterface? = monitor.interfaces.value.values.firstOrNull { it.interfaceName == selectedName }

    /** Null when the typed target is usable; otherwise why not. */
    fun freshGatewayReady(): Boolean = discoveryNetwork != null && discoveryNetwork == selected()?.network &&
        (discovery as? DiscoveryResult.Completed)?.gateways.orEmpty().any {
            it.protocol == com.aicarcompanion.enet.GatewayProtocol.HSFZ && it.address.toString() == targetText
        }

    fun targetProblem(): String? {
        val link = selected()?.ipv4 ?: return "Select an Ethernet interface with an IPv4 address first."
        if (targetText.isBlank()) return null
        val (_, verdict) = TargetPolicy.parseTarget(targetText, link)
        return (verdict as? TargetPolicy.Verdict.Rejected)?.reason
    }

    private fun link(): EnetLink? {
        val sel = selected() ?: return null
        val subnet = sel.ipv4 ?: return null
        selectedNetwork = sel.network
        log.add(sel.details)
        if (!sel.isEthernetTransport) log.add("WARNING: the selected network no longer reports the Ethernet transport.")
        return EnetLink(sel.interfaceName, subnet)
    }

    /** Non-invasive: connects to TCP 6801 on the typed gateway address and closes; sends nothing. */
    fun checkPort() {
        if (running != null) return
        val link = link() ?: return
        val target = Ipv4Address.parse(targetText) ?: return
        running = "Checking TCP port 6801 on $target…"
        viewModelScope.launch {
            portCheck = tester.checkTcpPort(link, target, timeoutMillis = connectTimeoutMillis)
            running = null
        }
    }

    fun discoverFresh() {
        if (running != null) return
        val candidates = monitor.interfaces.value.values.filter {
            it.isEthernetTransport && it.ipv4?.let { ip -> TargetPolicy.checkLocal(ip) == TargetPolicy.Verdict.Allowed } == true
        }
        val iface = candidates.firstOrNull { it.interfaceName == selectedName } ?: candidates.singleOrNull() ?: return
        selectedName = iface.interfaceName
        targetText = ""
        moduleStatus = ""
        moduleRows = emptyList()
        connection = null
        discoverGateway(autoSelect = true)
    }

    fun discover() = discoverGateway(autoSelect = false)

    private fun discoverGateway(autoSelect: Boolean) {
        if (running != null) return
        val link = link() ?: return
        val manual = targetText.takeIf { it.isNotBlank() }?.let(Ipv4Address::parse)
        val network = selectedNetwork
        discovery = null
        running = "Discovering gateways…"
        viewModelScope.launch {
            val found = tester.discover(link, DiscoveryOptions(manualTarget = manual, includeDoip = includeDoip, listenMillis = listenMillis))
            if (monitor.interfaces.value.containsKey(network)) {
                discoveryNetwork = network
                discovery = found
                if (autoSelect && selected()?.network == network && found is DiscoveryResult.Completed) {
                    val gateway = found.gateways.filter { it.protocol == com.aicarcompanion.enet.GatewayProtocol.HSFZ }.singleOrNull()
                    if (gateway != null) {
                        targetText = gateway.address.toString()
                        targetSelectionNotice = "Selected discovered gateway: ${gateway.address}"
                        log.add(targetSelectionNotice)
                    }
                }
            }
            running = null
        }
    }

    fun testConnection() {
        if (running != null) return
        val link = link() ?: return
        val target = Ipv4Address.parse(targetText) ?: return
        running = "Testing connection to $target…"
        viewModelScope.launch {
            connection = tester.testConnection(
                link, target,
                ConnectionOptions(
                    sendTesterPresent = sendTesterPresent, queryIgnition = queryIgnition, checkDoipPort = checkDoipPort,
                    connectTimeoutMillis = connectTimeoutMillis,
                ),
            )
            running = null
        }
    }

    var selectedModules by mutableStateOf(F10Module.entries.toSet())
        private set

    fun selectModule(module: F10Module, checked: Boolean) {
        if (running != null) return
        selectedModules = if (checked) selectedModules + module else selectedModules - module
    }

    fun selectAllModules(checked: Boolean) {
        if (running != null) return
        selectedModules = if (checked) F10Module.entries.toSet() else emptySet()
    }

    fun scanSelected() = runModules(false, F10Module.entries.filter { it in selectedModules })

    fun runModules(readVin: Boolean, modules: List<F10Module> = F10Module.entries) {
        if (running != null) return
        val scanModules = if (readVin) listOf(F10Module.DME) else modules.toList().distinct()
        if (scanModules.isEmpty()) { moduleStatus = "Select at least one module to scan"; return }
        val iface = selected() ?: return
        val subnet = iface.ipv4 ?: return
        val target = Ipv4Address.parse(targetText) ?: return
        val identity = (discovery as? DiscoveryResult.Completed)?.gateways
            ?.firstOrNull { discoveryNetwork == iface.network && it.address == target && it.vinIdentity != null }?.vinIdentity
        val binding = ModuleBinding(iface.network, subnet)
        moduleBinding = binding
        moduleActive = true
        moduleRows = emptyList()
        val total = scanModules.size
        var answered = 0
        val operationStarted = clock()
        var moduleStarted = operationStarted
        log.add("Starting ${if (readVin) "DME VIN read" else "module scan"} on $target: $total selected; 3 s reply timeout per module.")
        moduleStatus = "Connecting to $target"
        running = moduleStatus
        moduleJob = viewModelScope.launch {
            try {
                ModuleDiagnostics(JvmEnetTransport(binding), clock).run(
                    EnetLink(iface.interfaceName, subnet), target, connectTimeoutMillis, readVin,
                    selectedModules = scanModules,
                    progress = { module ->
                        moduleStarted = clock()
                        log.add("Checking ${module.title} (${module.name}, 0x%02X) on $target".format(module.address))
                        moduleStatus = "Checking ${module.title} (${module.name}), ${moduleRows.size + 1} of $total"
                    },
                    result = { module, reply ->
                        val outcome = when (reply) {
                            is ModuleReply.Positive -> "Answered"
                            is ModuleReply.Rejected -> "Answered, request declined (NRC %02X)".format(reply.code)
                            is ModuleReply.GatewayError -> "Gateway rejected routing (%04X)".format(reply.control)
                            ModuleReply.NoResponse -> "No response within 3 seconds"
                            ModuleReply.InvalidVin -> "Answered with an invalid VIN"
                            ModuleReply.Pending -> "Answered, final reply timed out"
                        }
                        if (reply !is ModuleReply.GatewayError && reply != ModuleReply.NoResponse) answered++
                        val line = "${module.title} (${module.name}): $outcome · ${clock() - moduleStarted} ms including connect"
                        log.add(line)
                        val vin = (reply as? ModuleReply.Positive)?.vin
                        val comparison = when {
                            vin == null || identity == null -> ""
                            identity.matches(vin) -> " · Matches gateway VIN"
                            else -> " · Differs from gateway VIN"
                        }
                        moduleRows = moduleRows + if (vin == null) line else "$line · $vin$comparison"
                    },
                )
                moduleStatus = "Finished on $target · $answered answered · ${moduleRows.size} of $total checked · ${clock() - operationStarted} ms total"
                log.add(moduleStatus)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                moduleStatus = "Stopped · ${e.message ?: e.javaClass.simpleName}"
            } finally {
                binding.close()
                moduleBinding = null
                running = null
                moduleActive = false
            }
        }
    }

    fun cancelModules(reason: String = "Cancelled") {
        moduleStatus = "$reason · ${moduleRows.size} checks completed"
        moduleJob?.cancel()
        moduleBinding?.close()
    }

    fun setManualTarget(value: String) {
        if (running != null) return
        targetText = value
        targetSelectionNotice = "Manually entered target: ${value.ifBlank { "none" }}"
    }

    fun useDiscovered(address: Ipv4Address) {
        if (running != null) return
        val previous = targetText.ifBlank { "none" }
        targetText = address.toString()
        targetSelectionNotice = "Selected discovered gateway: $address"
        log.add("User selected discovered gateway: $previous -> $address")
    }

    override fun onCleared() { moduleBinding?.close(); monitor.stop() }

    private companion object {
        const val KEY_LISTEN = "discovery_listen_ms"
        const val KEY_CONNECT = "tcp_connect_timeout_ms"
    }
}

/** Owns the diagnostic socket even during connect, before the transport returns a channel. */
private class ModuleBinding(val network: android.net.Network, val subnet: com.aicarcompanion.enet.Ipv4Subnet) : SocketBinder {
    private var active: Socket? = null
    private var closed = false

    @Synchronized override fun bind(socket: Socket) {
        if (closed) throw java.net.SocketException("Diagnostic check cancelled")
        active = socket
        network.bindSocket(socket)
    }

    @Synchronized fun close() {
        closed = true
        active?.close()
        active = null
    }
}
