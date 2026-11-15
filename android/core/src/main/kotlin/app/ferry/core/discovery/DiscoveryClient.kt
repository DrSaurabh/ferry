package app.ferry.core.discovery

import app.ferry.core.model.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress

/**
 * Finds Ferry devices on the LAN (design §3.5 step 2): [sends] requests [gapMs] apart to every address of
 * [targets] (the app passes 255.255.255.255 and the Wi-Fi interface's directed broadcast), always to UDP [port],
 * then collects replies until [listenMs] after the last send. Replies with another nonce are ignored; peers are
 * de-duplicated by host:port, in the order they first answered.
 *
 * A send that fails (e.g. one broadcast address not routable) is skipped; receive errors are skipped too.
 * Throws IOException only if [transport] cannot be created. Blocking work runs on [Dispatchers.IO]; the
 * coroutine can be cancelled at any time (receives wait at most 200 ms each).
 */
class DiscoveryClient(
    private val transport: () -> UdpTransport,
    private val targets: () -> List<InetAddress>,
    /** name, device_type of this device ("vivo X200 Pro", "phone"). */
    private val self: Pair<String, String>,
    private val port: Int = DiscoveryCodec.PORT,
    private val log: (String) -> Unit = {},
) {
    suspend fun discover(sends: Int = 3, gapMs: Long = 300, listenMs: Long = 2_000): List<Peer> =
        withContext(Dispatchers.IO) {
            val nonce = DiscoveryCodec.newNonce()
            val request = DiscoveryCodec.request(self.first, self.second, nonce)
            val found = LinkedHashMap<String, Peer>()
            val buf = ByteArray(2048)
            transport().use { t ->
                val start = System.nanoTime()
                fun elapsedMs() = (System.nanoTime() - start) / 1_000_000
                val end = (sends - 1).coerceAtLeast(0) * gapMs + listenMs
                var sent = 0
                while (true) {
                    ensureActive()
                    val now = elapsedMs()
                    if (sent < sends && now >= sent * gapMs) {
                        for (to in targets()) {
                            try {
                                t.send(request, to, port)
                            } catch (e: IOException) {
                                if (sent == 0) log("discovery: send to ${to.hostAddress}: $e")
                            }
                        }
                        sent++
                        continue
                    }
                    if (now >= end) break
                    val nextEvent = if (sent < sends) sent * gapMs else end
                    val wait = (nextEvent - now).coerceIn(1, 200).toInt()
                    val d = try {
                        t.receive(buf, wait)
                    } catch (e: IOException) {
                        Thread.sleep(10) // e.g. an ICMP "port unreachable" surfacing on the next receive
                        null
                    } ?: continue
                    val peer = DiscoveryCodec.parseReply(buf, d.length, nonce, d.address) ?: continue
                    val key = "${peer.host}:${peer.port}"
                    if (key !in found) found[key] = peer
                }
            }
            found.values.toList()
        }
}
