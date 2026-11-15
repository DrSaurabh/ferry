package app.ferry.core.discovery

import java.io.IOException
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scriptable [UdpTransport]. Incoming datagrams (or errors) are queued with [deliver]/[fail]; [onSend] can
 * answer a send by queueing replies (a fake LAN). Everything sent is recorded in [sent].
 */
class FakeUdp(private val onSend: (FakeUdp.(data: ByteArray, to: InetAddress, port: Int) -> Unit)? = null) : UdpTransport {
    class Sent(val data: ByteArray, val to: InetAddress, val port: Int) {
        val text get() = String(data, Charsets.UTF_8)
    }

    private sealed interface In
    private class Packet(val data: ByteArray, val from: InetAddress, val port: Int) : In
    private class Error(val e: Exception) : In

    private val incoming = LinkedBlockingQueue<In>()
    val sent: MutableList<Sent> = Collections.synchronizedList(mutableListOf())
    val receives = AtomicInteger()
    @Volatile var closed = false

    fun deliver(data: ByteArray, from: InetAddress = InetAddress.getByName("192.168.1.20"), port: Int = 40000) {
        incoming.add(Packet(data, from, port))
    }

    fun fail(e: Exception) {
        incoming.add(Error(e))
    }

    override fun send(data: ByteArray, to: InetAddress, port: Int) {
        if (closed) throw IOException("closed")
        sent += Sent(data.copyOf(), to, port)
        onSend?.invoke(this, data, to, port)
    }

    override fun receive(buffer: ByteArray, timeoutMs: Int): Datagram? {
        if (closed) throw IOException("closed")
        receives.incrementAndGet()
        return when (val x = incoming.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
            null -> null
            is Error -> throw x.e
            is Packet -> {
                System.arraycopy(x.data, 0, buffer, 0, x.data.size)
                Datagram(x.data.size, x.from, x.port)
            }
        }
    }

    override fun close() {
        closed = true
    }
}
