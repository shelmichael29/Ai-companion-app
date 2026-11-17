package com.aicarcompanion.app.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Network transports that matter for diagnostics. */
enum class NetTransport { WIFI, CELLULAR, ETHERNET, VPN, OTHER }

/** What Android reports about the default network. Pure data, so the rules below are unit-testable. */
data class NetworkSnapshot(
    val hasInternetCapability: Boolean,
    val validated: Boolean,
    val captivePortal: Boolean,
    val transports: Set<NetTransport>,
)

/** Whether Penelope can reach cloud services right now, and why. */
data class InternetState(val online: Boolean, val reason: String, val transports: Set<NetTransport> = emptySet()) {
    companion object {
        /** Before Android's first report: assume online so nothing is blocked by a missing callback. */
        val UNKNOWN = InternetState(online = true, reason = "Not checked yet")

        /**
         * Decides from the phone's **default** network, the one cloud requests actually use.
         * - Online only when Android has validated real internet access (not just a Wi-Fi link).
         * - A captive portal (hotel or airport Wi-Fi sign-in) counts as offline.
         * - A local vehicle network (e.g. BMW ENET over USB Ethernet) has no internet capability.
         *   Android keeps a validated mobile or Wi-Fi network as the default, so cloud requests
         *   don't use it. Penelope never binds the whole app to such a network
         *   (see NetworkRoutingGuardTest).
         */
        fun from(defaultNetwork: NetworkSnapshot?): InternetState = when {
            defaultNetwork == null -> InternetState(false, "No network connection")
            !defaultNetwork.hasInternetCapability -> InternetState(false, "The current network has no internet access", defaultNetwork.transports)
            defaultNetwork.captivePortal -> InternetState(false, "This Wi-Fi needs you to sign in", defaultNetwork.transports)
            !defaultNetwork.validated -> InternetState(false, "Connected, but the internet isn't reachable yet", defaultNetwork.transports)
            else -> InternetState(true, "Online", defaultNetwork.transports)
        }
    }
}

/** Live internet availability. Android implementation below; tests use a plain flow. */
interface InternetMonitor {
    val state: StateFlow<InternetState>
}

/**
 * Watches the phone's default network with [ConnectivityManager.registerDefaultNetworkCallback].
 * It only observes; it never requests or switches networks and needs no permission dialog
 * (ACCESS_NETWORK_STATE is a normal permission).
 */
class AndroidInternetMonitor(context: Context, private val log: ConnectionLog? = null) : InternetMonitor {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(InternetState.UNKNOWN)
    override val state: StateFlow<InternetState> = _state.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(snapshot(caps))
        override fun onLost(network: Network) = update(null)
    }

    init {
        runCatching {
            update(cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.let(::snapshot))
            cm?.registerDefaultNetworkCallback(callback)
        }.onFailure { log?.record(LogArea.INTERNET, "Network monitoring unavailable", mapOf("error" to it.javaClass.simpleName)) }
    }

    private fun update(snapshot: NetworkSnapshot?) {
        val next = InternetState.from(snapshot)
        val previous = _state.value
        _state.value = next
        if (previous.online != next.online || previous.reason != next.reason) {
            log?.record(
                LogArea.INTERNET, if (next.online) "Online" else "Offline",
                mapOf("reason" to next.reason, "transport" to next.transports.joinToString("+").ifEmpty { "none" }),
            )
        }
    }

    private fun snapshot(caps: NetworkCapabilities) = NetworkSnapshot(
        hasInternetCapability = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        captivePortal = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
        transports = buildSet {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(NetTransport.WIFI)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(NetTransport.CELLULAR)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(NetTransport.ETHERNET)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(NetTransport.VPN)
            if (isEmpty()) add(NetTransport.OTHER)
        },
    )
}
