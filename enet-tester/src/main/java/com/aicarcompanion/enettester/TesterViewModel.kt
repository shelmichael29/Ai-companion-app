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
    var targetText by mutableStateOf("")
    var includeDoip by mutableStateOf(true)
    var sendTesterPresent by mutableStateOf(true)
    var queryIgnition by mutableStateOf(false)
    var checkDoipPort by mutableStateOf(false)
    private val prefs = app.getSharedPreferences("enet_tester", android.content.Context.MODE_PRIVATE)

    /** Advanced: how long discovery listens for HSFZ/DoIP answers (default 5 s). */
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

    fun selected(): EthernetInterface? = monitor.interfaces.value.values.firstOrNull { it.interfaceName == selectedName }

    /** Null when the typed target is usable; otherwise why not. */
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
        val link = link() ?: return
        val target = Ipv4Address.parse(targetText) ?: return
        running = "Checking TCP port 6801 on $target…"
        viewModelScope.launch {
            portCheck = tester.checkTcpPort(link, target, timeoutMillis = connectTimeoutMillis)
            running = null
        }
    }

    fun discover() {
        val link = link() ?: return
        val manual = targetText.takeIf { it.isNotBlank() }?.let(Ipv4Address::parse)
        running = "Discovering gateways…"
        viewModelScope.launch {
            discovery = tester.discover(link, DiscoveryOptions(manualTarget = manual, includeDoip = includeDoip, listenMillis = listenMillis))
            running = null
        }
    }

    fun testConnection() {
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

    fun useDiscovered(address: Ipv4Address) { targetText = address.toString() }

    override fun onCleared() { monitor.stop() }

    private companion object {
        const val KEY_LISTEN = "discovery_listen_ms"
        const val KEY_CONNECT = "tcp_connect_timeout_ms"
    }
}
