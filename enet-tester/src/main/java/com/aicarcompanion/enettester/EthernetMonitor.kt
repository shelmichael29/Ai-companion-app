package com.aicarcompanion.enettester

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.aicarcompanion.enet.Ipv4Address
import com.aicarcompanion.enet.Ipv4Subnet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address

/** One Ethernet network Android reports (a USB Ethernet adapter shows up as TRANSPORT_ETHERNET). */
data class EthernetInterface(
    val network: Network,
    val interfaceName: String,
    /** First IPv4 address with prefix, or null while none is assigned. */
    val ipv4: Ipv4Subnet?,
    /** What Android reports about this network, for the diagnostic log (routes, transports, validation). */
    val details: String = "",
    val isEthernetTransport: Boolean = true,
)

/**
 * Watches Ethernet networks only. Wi-Fi, mobile data and VPNs are never listed, so they can't be
 * selected for probing. The car network has no internet, so the request drops that capability.
 */
class EthernetMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _interfaces = MutableStateFlow<Map<Network, EthernetInterface>>(emptyMap())
    val interfaces: StateFlow<Map<Network, EthernetInterface>> = _interfaces.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = update(network, lp, cm.getNetworkCapabilities(network))
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            cm.getLinkProperties(network)?.let { update(network, it, caps) }
        }
        override fun onAvailable(network: Network) { cm.getLinkProperties(network)?.let { update(network, it, cm.getNetworkCapabilities(network)) } }
        override fun onLost(network: Network) = _interfaces.update { it - network }
    }

    private fun update(network: Network, lp: LinkProperties, caps: NetworkCapabilities?) {
        val v4 = lp.linkAddresses.firstOrNull { it.address is Inet4Address }
        val subnet = v4?.let { Ipv4Subnet(Ipv4Address.of(it.address.address), it.prefixLength) }
        val ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ?: true
        val details = buildString {
            append("Android network $network, interface ${lp.interfaceName}")
            append(", transport ${if (ethernet) "ETHERNET" else "other"}")
            if (caps != null) {
                append(", internet=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}")
                append(", validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
            }
            append(", addresses=${lp.linkAddresses.joinToString { it.toString() }.ifEmpty { "none" }}")
            append(", routes=${lp.routes.joinToString { "${it.destination}→${it.gateway?.hostAddress ?: "on-link"}" }.ifEmpty { "none" }}")
        }
        _interfaces.update { it + (network to EthernetInterface(network, lp.interfaceName ?: "ethernet", subnet, details, ethernet)) }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(request, callback)
    }

    fun stop() = runCatching { cm.unregisterNetworkCallback(callback) }
}
