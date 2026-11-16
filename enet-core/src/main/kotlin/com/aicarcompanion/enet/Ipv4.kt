package com.aicarcompanion.enet

/** An IPv4 address as an unsigned 32-bit value. */
@JvmInline
value class Ipv4Address(val bits: Int) {

    val octets: IntArray get() = intArrayOf(bits ushr 24 and 0xFF, bits ushr 16 and 0xFF, bits ushr 8 and 0xFF, bits and 0xFF)

    /** RFC 1918 private ranges: 10/8, 172.16/12, 192.168/16. */
    val isPrivate: Boolean
        get() = (bits ushr 24) == 10 ||
            (bits ushr 20) == (172 shl 4 or 1) ||
            (bits ushr 16) == (192 shl 8 or 168)

    /** RFC 3927 link-local 169.254/16. BMW ZGWs fall back to this without DHCP. */
    val isLinkLocal: Boolean get() = (bits ushr 16) == (169 shl 8 or 254)

    val isLoopback: Boolean get() = (bits ushr 24) == 127
    val isMulticast: Boolean get() = (bits ushr 28) == 0xE
    val isUnspecified: Boolean get() = bits == 0
    val isLimitedBroadcast: Boolean get() = bits == -1

    override fun toString(): String = octets.joinToString(".")

    companion object {
        val LIMITED_BROADCAST = Ipv4Address(-1)

        /** Parses dotted-quad notation strictly (no leading zeros, no hostnames). Null if invalid. */
        fun parse(text: String): Ipv4Address? {
            val parts = text.trim().split('.')
            if (parts.size != 4) return null
            var value = 0
            for (p in parts) {
                if (p.isEmpty() || p.length > 3 || !p.all(Char::isDigit) || (p.length > 1 && p[0] == '0')) return null
                val n = p.toInt()
                if (n > 255) return null
                value = (value shl 8) or n
            }
            return Ipv4Address(value)
        }

        fun of(bytes: ByteArray): Ipv4Address {
            require(bytes.size == 4)
            return Ipv4Address(bytes.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) })
        }
    }
}

fun Ipv4Address.toBytes(): ByteArray = octets.map { it.toByte() }.toByteArray()

/** An interface address with its prefix length, e.g. 169.254.12.7/16. */
data class Ipv4Subnet(val address: Ipv4Address, val prefixLength: Int) {
    init { require(prefixLength in 0..32) { "prefix $prefixLength" } }

    private val mask: Int get() = if (prefixLength == 0) 0 else (-1 shl (32 - prefixLength))

    val network: Ipv4Address get() = Ipv4Address(address.bits and mask)

    /** Directed broadcast for this subnet (only meaningful for prefixes shorter than /31). */
    val broadcast: Ipv4Address get() = Ipv4Address(address.bits or mask.inv())

    operator fun contains(other: Ipv4Address): Boolean = (other.bits and mask) == (address.bits and mask)

    override fun toString() = "$address/$prefixLength"
}
