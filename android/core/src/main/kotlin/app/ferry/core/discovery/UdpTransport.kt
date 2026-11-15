package app.ferry.core.discovery

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** One received datagram: [length] bytes at the start of the caller's buffer, from [address]:[port]. */
class Datagram(val length: Int, val address: InetAddress, val port: Int)

/** UDP socket seam (design §3.5): the app binds it to the Wi-Fi network [A9]; tests fake it. */
interface UdpTransport : java.io.Closeable {
    fun send(data: ByteArray, to: InetAddress, port: Int)

    /** null on timeout. Throws IOException on socket errors (callers decide whether to go on). */
    fun receive(buffer: ByteArray, timeoutMs: Int): Datagram?
}

/**
 * [UdpTransport] over a [DatagramSocket] bound to [localPort] (0 = any free port) on all interfaces.
 * [binder] runs after the bind (on Android `Network::bindSocket`, which would otherwise bind the socket to a
 * random port itself) [A9].
 */
class DatagramTransport(
    localPort: Int = 0,
    broadcast: Boolean = true,
    binder: (DatagramSocket) -> Unit = {},
) : UdpTransport {
    private val socket = DatagramSocket(null as java.net.SocketAddress?)

    init {
        try {
            socket.reuseAddress = false
            socket.broadcast = broadcast
            socket.bind(InetSocketAddress(localPort))
            binder(socket)
        } catch (e: Exception) {
            socket.close()
            throw if (e is IOException) e else IOException(e.toString(), e)
        }
    }

    /** The bound local port. */
    val localPort: Int get() = socket.localPort

    override fun send(data: ByteArray, to: InetAddress, port: Int) {
        socket.send(DatagramPacket(data, data.size, to, port))
    }

    override fun receive(buffer: ByteArray, timeoutMs: Int): Datagram? {
        socket.soTimeout = timeoutMs.coerceAtLeast(1) // 0 would mean "for ever"
        val p = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(p)
            Datagram(p.length, p.address, p.port)
        } catch (e: SocketTimeoutException) {
            null
        }
    }

    override fun close() = socket.close()
}
