package app.ferry.net

/** One network as `NetworkBinder` sees it (pure data, so the choice can be unit-tested). */
data class NetworkCandidate(
    val id: Long,
    val wifi: Boolean,
    val ethernet: Boolean,
    val cellular: Boolean,
    val active: Boolean,
)

/**
 * Which network Ferry binds every socket to (plan §4.2, design §3.5) [A9] [PR2]: Wi-Fi, else Ethernet (TV
 * adapters), else the active network if it is not cellular (emulators, unusual TVs). null = "not on Wi-Fi"
 * (no network, or cellular only). [anyNetworkOverride] (debug builds only, `app.ferry.debug.NET=any`) takes the
 * active network whatever its transport.
 */
fun chooseNetwork(candidates: List<NetworkCandidate>, anyNetworkOverride: Boolean = false): NetworkCandidate? {
    if (anyNetworkOverride) return candidates.firstOrNull { it.active } ?: candidates.firstOrNull()
    return candidates.firstOrNull { it.wifi && it.active }
        ?: candidates.firstOrNull { it.wifi }
        ?: candidates.firstOrNull { it.ethernet }
        ?: candidates.firstOrNull { it.active && !it.cellular }
}

/** Directed broadcast of an IPv4 interface address (plan §4.13 BroadcastAddressTest). */
object BroadcastAddress {
    /** 192.168.1.23/24 → 192.168.1.255. null for non-IPv4 input or prefixes without a broadcast (0, ≥ 31). */
    fun directed(ipv4: ByteArray, prefix: Int): ByteArray? {
        if (ipv4.size != 4 || prefix < 1 || prefix > 30) return null
        val ip = ((ipv4[0].toInt() and 0xff) shl 24) or ((ipv4[1].toInt() and 0xff) shl 16) or
            ((ipv4[2].toInt() and 0xff) shl 8) or (ipv4[3].toInt() and 0xff)
        val mask = -1 shl (32 - prefix)
        val b = ip or mask.inv()
        return byteArrayOf((b ushr 24).toByte(), (b ushr 16).toByte(), (b ushr 8).toByte(), b.toByte())
    }

    /** "192.168.1." from "192.168.1.23" (TV "Enter address" prefill, design §5.1 [A18]); "" if not IPv4. */
    fun firstThreeOctets(ipv4: String?): String {
        val parts = ipv4?.split('.') ?: return ""
        return if (parts.size == 4) parts.take(3).joinToString(".", postfix = ".") else ""
    }
}
