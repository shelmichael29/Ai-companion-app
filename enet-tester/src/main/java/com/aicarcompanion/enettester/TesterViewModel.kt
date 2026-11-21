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
import com.aicarcompanion.enet.EngineVinOptions
import com.aicarcompanion.enet.EngineVinResult
import com.aicarcompanion.enet.EnetTester
import com.aicarcompanion.enet.ModuleScanEntry
import com.aicarcompanion.enet.ModuleScanResult
import com.aicarcompanion.enet.ScanOptions
import com.aicarcompanion.enet.ScanProgress
import com.aicarcompanion.enet.vinReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

    init {
        // The cable coming out (the selected Ethernet network disappearing) stops whatever is running and frees the connection.
        viewModelScope.launch {
            monitor.interfaces.collect { interfaces ->
                val net = selectedNetwork
                if (net != null && net !in interfaces && job?.isActive == true) {
                    log.add("Ethernet network $net lost (cable removed?): stopping the running operation and releasing the connection.")
                    stop("The Ethernet connection was lost, so the operation stopped and the connection was released.")
                }
            }
        }
    }

    var selectedName by mutableStateOf<String?>(null)
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

    /** The one running operation, so Cancel and a pulled cable can stop it and release its connection. */
    private var job: Job? = null

    var engineVin by mutableStateOf<EngineVinResult?>(null)
        private set
    var scanProgress by mutableStateOf<ScanProgress?>(null)
        private set
    var scanResult by mutableStateOf<ModuleScanResult?>(null)
        private set

    /** Why the last operation stopped early (cancelled, cable removed), shown with the partial results. */
    var stoppedNotice by mutableStateOf<String?>(null)
        private set

    val canCancel: Boolean get() = job?.isActive == true
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
        start("Checking TCP port 6801 on $target…") {
            portCheck = tester.checkTcpPort(link, target, timeoutMillis = connectTimeoutMillis)
        }
    }

    fun discover() {
        val link = link() ?: return
        val manual = targetText.takeIf { it.isNotBlank() }?.let(Ipv4Address::parse)
        start("Discovering gateways…") {
            discovery = tester.discover(link, DiscoveryOptions(manualTarget = manual, includeDoip = includeDoip, listenMillis = listenMillis))
        }
    }

    fun testConnection() {
        val link = link() ?: return
        val target = Ipv4Address.parse(targetText) ?: return
        start("Testing connection to $target…") {
            connection = tester.testConnection(
                link, target,
                ConnectionOptions(
                    sendTesterPresent = sendTesterPresent, queryIgnition = queryIgnition, checkDoipPort = checkDoipPort,
                    connectTimeoutMillis = connectTimeoutMillis,
                ),
            )
        }
    }

    /** Runs one operation as the single cancellable job. */
    private fun start(message: String, block: suspend () -> Unit) {
        stoppedNotice = null
        running = message
        job = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } finally {
                running = null
            }
        }
    }

    /** Stops the running operation (Cancel button or lost cable). Its connection is closed by the operation itself. */
    fun stop(notice: String = "Cancelled. The connection was released.") {
        val active = job?.takeIf { it.isActive } ?: return
        stoppedNotice = notice
        active.cancel()
        running = null
    }

    /** Reads the VIN from the engine computer through the gateway, and compares it with the gateway's own VIN if discovery found one. */
    fun readEngineVin() {
        val link = link() ?: return
        val target = Ipv4Address.parse(targetText) ?: return
        val gatewayVin = (discovery as? DiscoveryResult.Completed)?.gateways?.firstOrNull { it.address == target }?.vinReference()
        engineVin = null
        start("Asking the engine computer for its VIN…") {
            engineVin = tester.readEngineVin(link, target, EngineVinOptions(gatewayVin = gatewayVin, connectTimeoutMillis = connectTimeoutMillis))
        }
    }

    /** Checks the documented F10 modules one at a time. Progress is shown as it goes; Cancel stops it. */
    fun scanModules() {
        val link = link() ?: return
        val target = Ipv4Address.parse(targetText) ?: return
        scanResult = null
        scanProgress = null
        start("Scanning modules…") {
            scanResult = tester.scanModules(link, target, ScanOptions(connectTimeoutMillis = connectTimeoutMillis)) { scanProgress = it }
        }
    }

    fun useDiscovered(address: Ipv4Address) { targetText = address.toString() }

    override fun onCleared() { monitor.stop() }

    private companion object {
        const val KEY_LISTEN = "discovery_listen_ms"
        const val KEY_CONNECT = "tcp_connect_timeout_ms"
    }
}
