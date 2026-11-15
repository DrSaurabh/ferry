package app.ferry.core.discovery

import app.ferry.core.FerryJson
import app.ferry.core.model.DiscoveryReply
import app.ferry.core.model.DiscoveryRequest
import app.ferry.core.model.Peer
import app.ferry.core.util.Hex
import java.net.InetAddress

/**
 * Encodes and parses discovery datagrams (design §2.4): UTF-8 JSON, at most [MAX_BYTES]. Every parser returns
 * null for anything that is not a well-formed Ferry datagram (too long, not JSON, wrong app, wrong nonce…),
 * so a stray packet on the LAN is ignored silently.
 */
object DiscoveryCodec {
    const val PORT = 8765
    const val MAX_BYTES = 512
    const val PROTOCOL = 2

    /** Same rule as ferry.py's `RX_NONCE`. */
    private val NONCE = Regex("^[0-9a-fA-F]{8,32}$")

    /** A device name longer than this is cut, so a request always fits in [MAX_BYTES]. */
    private const val NAME_MAX = 60

    fun newNonce(random: java.util.Random = java.security.SecureRandom()): String =
        Hex.encode(ByteArray(4).also { random.nextBytes(it) })

    fun request(name: String, deviceType: String, nonce: String): ByteArray {
        val r = DiscoveryRequest(name = cut(name), deviceType = cut(deviceType), nonce = nonce)
        return FerryJson.encodeToString(DiscoveryRequest.serializer(), r).toByteArray(Charsets.UTF_8)
    }

    fun parseRequest(data: ByteArray, len: Int): DiscoveryRequest? {
        val r = decode(data, len) { FerryJson.decodeFromString(DiscoveryRequest.serializer(), it) } ?: return null
        if (r.app != "ferry" || r.q != "discover" || !NONCE.matches(r.nonce)) return null
        return r
    }

    fun reply(r: DiscoveryReply): ByteArray {
        val c = r.copy(name = cut(r.name))
        return FerryJson.encodeToString(DiscoveryReply.serializer(), c).toByteArray(Charsets.UTF_8)
    }

    fun parseReply(data: ByteArray, len: Int, expectedNonce: String, from: InetAddress): Peer? {
        val r = decode(data, len) { FerryJson.decodeFromString(DiscoveryReply.serializer(), it) } ?: return null
        if (r.app != "ferry" || r.nonce != expectedNonce || r.port !in 1..65535 || r.deviceType.isEmpty()) return null
        val host = from.hostAddress ?: return null
        return Peer(r.name, r.deviceType, host, r.port, r.version, r.protocol)
    }

    private fun cut(s: String): String {
        if (s.length <= NAME_MAX) return s
        var end = NAME_MAX
        if (Character.isHighSurrogate(s[end - 1])) end-- // never split an emoji
        return s.substring(0, end)
    }

    private inline fun <T> decode(data: ByteArray, len: Int, parse: (String) -> T): T? {
        if (len <= 0 || len > MAX_BYTES || len > data.size) return null
        return try {
            parse(String(data, 0, len, Charsets.UTF_8))
        } catch (e: Exception) { // kotlinx SerializationException (IllegalArgumentException), wrong JSON shape
            null
        }
    }
}
