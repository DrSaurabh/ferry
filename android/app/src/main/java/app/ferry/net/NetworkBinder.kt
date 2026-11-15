package app.ferry.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import app.ferry.core.net.FerryHttp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Tracks the network Ferry uses and binds every socket to it (design §3.5 step 0, plan §4.2) [A9]: the shared
 * OkHttpClient uses that network's socket factory, discovery sockets are bound with [bindDatagram]. The choice
 * is [chooseNetwork] (Wi-Fi > Ethernet > active non-cellular). No network → [chosen] is null → "not on Wi-Fi".
 * The shared client is rebuilt when the chosen network changes; the old one finishes its in-flight calls.
 */
class NetworkBinder(
    private val ctx: Context,
    private val deviceName: () -> String,
    private val appVersion: String,
    private val log: (String) -> Unit,
) {
    data class Chosen(val network: Network, val kind: String, val ipv4: Inet4Address?, val prefix: Int)

    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val _chosen = MutableStateFlow<Chosen?>(null)
    val chosen: StateFlow<Chosen?> = _chosen

    @Volatile private var client: OkHttpClient? = null
    @Volatile private var clientNetwork: Network? = null

    /** Debug builds only (`app.ferry.debug.NET=any`) [PR2]: use the active network whatever its transport. */
    @Volatile var anyNetworkOverride = false
        set(v) {
            field = v
            evaluate()
        }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = evaluate()
        override fun onLost(network: Network) = evaluate()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = evaluate()
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = evaluate()
    }

    fun start() {
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = evaluate()
                override fun onLost(network: Network) = evaluate()
            })
        } catch (e: RuntimeException) { // e.g. too many callbacks; the first evaluate still works
            log("network callback not registered: $e")
        }
        evaluate()
    }

    /** Re-reads every network and picks one. Called from ConnectivityManager's thread and from [start]. */
    @Synchronized
    fun evaluate() {
        val active = try { cm.activeNetwork } catch (e: RuntimeException) { null }
        @Suppress("DEPRECATION")
        val all = try { cm.allNetworks.toList() } catch (e: RuntimeException) { emptyList() }
        val nets = ArrayList<Network>()
        val cands = ArrayList<NetworkCandidate>()
        for (n in all) {
            val caps = try { cm.getNetworkCapabilities(n) } catch (e: RuntimeException) { null } ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            cands += NetworkCandidate(
                id = nets.size.toLong(),
                wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                active = n == active,
            )
            nets += n
        }
        val pick = chooseNetwork(cands, anyNetworkOverride)
        val new = pick?.let { c ->
            val n = nets[c.id.toInt()]
            val lp = try { cm.getLinkProperties(n) } catch (e: RuntimeException) { null }
            val la = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }
            val kind = when {
                c.wifi -> "wifi"
                c.ethernet -> "ethernet"
                c.cellular -> "cellular"
                else -> "other"
            }
            Chosen(n, kind, la?.address as Inet4Address?, la?.prefixLength ?: 0)
        }
        if (new != _chosen.value) {
            log("network: " + (new?.let { "${it.kind} ${it.ipv4?.hostAddress}/${it.prefix}" } ?: "none") +
                " (of ${cands.size}${if (anyNetworkOverride) ", NET=any" else ""})")
            _chosen.value = new
        }
    }

    /** The shared client for the chosen network (rebuilt when it changes). */
    fun httpClient(): OkHttpClient {
        val net = _chosen.value?.network
        val c = client
        if (c != null && net == clientNetwork) return c
        synchronized(this) {
            val again = client
            if (again != null && net == clientNetwork) return again
            val fresh = FerryHttp.newClient(net?.socketFactory, deviceName(), appVersion)
            client = fresh
            clientNetwork = net
            return fresh
        }
    }

    /** Binds a discovery socket to the chosen network (after its bind, see `DatagramTransport`) [A9]. */
    fun bindDatagram(socket: DatagramSocket) {
        _chosen.value?.network?.bindSocket(socket)
    }

    /** 255.255.255.255 and the interface's directed broadcast (design §3.5 step 2). */
    fun broadcastTargets(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        out += InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))
        val c = _chosen.value
        val ip = c?.ipv4
        if (ip != null) BroadcastAddress.directed(ip.address, c.prefix)?.let { out += InetAddress.getByAddress(it) }
        return out
    }

    val hasNetwork: Boolean get() = _chosen.value != null

    /** This device's IPv4 on the chosen network (TV "Enter address" prefill [A18]). */
    val ownIpv4: String? get() = _chosen.value?.ipv4?.hostAddress
}
