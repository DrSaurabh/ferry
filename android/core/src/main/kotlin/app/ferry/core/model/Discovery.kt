package app.ferry.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Discovery datagrams (design §2.4, §5.5): UTF-8 JSON, at most 512 bytes, UDP 8765.

/** `{"app":"ferry","q":"discover","protocol":2,"name":…,"device_type":"phone","nonce":"<8 hex>"}` */
@Serializable
data class DiscoveryRequest(
    val app: String = "ferry",
    val q: String = "discover",
    val protocol: Int = 2,
    val name: String = "",
    @SerialName("device_type") val deviceType: String = "",
    val nonce: String = "",
)

/**
 * `{"app":"ferry","protocol":2,"nonce":…,"name":…,"device_type":"pc"|"tv","port":N,"version":…}`.
 * [port] is the sender's TCP port (PC: its real `--port` [B16]; TV: 8766).
 */
@Serializable
data class DiscoveryReply(
    val app: String = "ferry",
    val protocol: Int = 2,
    val nonce: String = "",
    val name: String = "",
    @SerialName("device_type") val deviceType: String = "",
    val port: Int = 0,
    val version: String = "",
)

/** A device that answered a discovery broadcast; [host] is the datagram's source address. */
data class Peer(
    val name: String,
    val deviceType: String,
    val host: String,
    val port: Int,
    val version: String,
    val protocol: Int,
)
