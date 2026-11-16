package com.aicarcompanion.enet

/**
 * Decides which addresses may ever be probed. Only private (RFC 1918) and link-local
 * (169.254/16) unicast addresses on the user-selected Ethernet link are allowed, so the
 * tester can never reach the internet or scan arbitrary networks.
 */
object TargetPolicy {

    sealed interface Verdict {
        data object Allowed : Verdict
        data class Rejected(val reason: String) : Verdict
    }

    /** The phone's own address on the selected interface must itself be private or link-local. */
    fun checkLocal(link: Ipv4Subnet): Verdict {
        val a = link.address
        return when {
            a.isUnspecified -> Verdict.Rejected("The Ethernet interface has no IPv4 address yet.")
            !(a.isPrivate || a.isLinkLocal) -> Verdict.Rejected(
                "The selected interface has a public address ($a). Testing is limited to a private or link-local car network.",
            )
            link.prefixLength < 16 -> Verdict.Rejected("The interface network (/${link.prefixLength}) is larger than a car network; refusing to broadcast on it.")
            else -> Verdict.Allowed
        }
    }

    /** A unicast target the user typed or discovery reported. */
    fun checkTarget(target: Ipv4Address, link: Ipv4Subnet): Verdict = when {
        target.isUnspecified || target.isLimitedBroadcast -> Verdict.Rejected("$target is not a single device address.")
        target.isLoopback -> Verdict.Rejected("$target is this phone (loopback).")
        target.isMulticast -> Verdict.Rejected("$target is a multicast address.")
        !(target.isPrivate || target.isLinkLocal) -> Verdict.Rejected("$target is a public internet address. Only private or link-local car addresses are allowed.")
        target == link.address -> Verdict.Rejected("$target is this phone's own address.")
        target !in link -> Verdict.Rejected("$target is not on the selected Ethernet network (${link.network}/${link.prefixLength}).")
        link.prefixLength < 31 && (target == link.broadcast || target == link.network) -> Verdict.Rejected("$target is a network or broadcast address.")
        else -> Verdict.Allowed
    }

    /** Parses and checks a typed target in one step. */
    fun parseTarget(text: String, link: Ipv4Subnet): Pair<Ipv4Address?, Verdict> {
        val ip = Ipv4Address.parse(text) ?: return null to Verdict.Rejected("\"${text.trim()}\" isn't a valid IPv4 address (e.g. 169.254.1.1).")
        return ip to checkTarget(ip, link)
    }
}
