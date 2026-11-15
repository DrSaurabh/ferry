package app.ferry.core.discovery

import app.ferry.core.model.DiscoveryReply
import app.ferry.core.model.Peer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong

/** TV-side responder (design §5.5, plan §3.7) on loopback and on a scripted transport. */
class DiscoveryResponderTest {
    private val closeables = mutableListOf<java.io.Closeable>()
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val tv = DiscoveryReply(name = "Living room TV", deviceType = "tv", port = 8766, version = "2.0.7")

    @After
    fun tearDown() = closeables.forEach { runCatching { it.close() } }

    private fun <T : java.io.Closeable> keep(c: T): T = c.also { closeables += it }

    private fun waitFor(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    @Test
    fun loopbackReplyWithTvIdentityAndPort8766(): Unit = runBlocking {
        val t = keep(DatagramTransport(0, broadcast = false))
        keep(DiscoveryResponder(t, { tv }, log = { println("[responder] $it") })).start()
        val client = DiscoveryClient({ DatagramTransport() }, { listOf(loopback) }, "JVM phone" to "phone", t.localPort)
        val peers = client.discover(sends = 3, gapMs = 100, listenMs = 700)
        assertEquals(listOf(Peer("Living room TV", "tv", "127.0.0.1", 8766, "2.0.7", 2)), peers) // 3 replies, one peer
    }

    @Test
    fun garbageIsIgnoredAndTheLoopGoesOn() {
        val t = keep(DatagramTransport(0, broadcast = false))
        val responder = keep(DiscoveryResponder(t, { tv }))
        responder.start()
        val s = keep(DatagramSocket())
        s.soTimeout = 1_000
        fun send(b: ByteArray) = s.send(DatagramPacket(b, b.size, loopback, t.localPort))
        send("hello".toByteArray())
        send(ByteArray(600) { '{'.code.toByte() })
        send("""{"app":"ferry","q":"discover","nonce":"nothex!!"}""".toByteArray())
        send("""{"app":"other","q":"discover","nonce":"0123abcd"}""".toByteArray())
        try {
            s.receive(DatagramPacket(ByteArray(2048), 2048))
            throw AssertionError("garbage was answered")
        } catch (e: SocketTimeoutException) {
            // expected: silence
        }
        send(DiscoveryCodec.request("p", "phone", "0123abcd"))
        val p = DatagramPacket(ByteArray(2048), 2048)
        s.receive(p)
        val peer = DiscoveryCodec.parseReply(p.data, p.length, "0123abcd", p.address)
        assertEquals("tv", peer!!.deviceType)
        assertEquals(1, responder.replies)
    }

    @Test
    fun atMost20RepliesPerSecond() {
        val now = AtomicLong(1_000_000)
        val t = FakeUdp()
        val responder = keep(DiscoveryResponder(t, { tv }, nowMs = { now.get() }))
        repeat(50) { t.deliver(DiscoveryCodec.request("p$it", "phone", "0000%04x".format(it))) }
        responder.start()
        waitFor("50 requests read") { t.receives.get() > 50 }
        assertEquals(20, t.sent.size)
        now.addAndGet(999)
        t.deliver(DiscoveryCodec.request("late", "phone", "0123abcd"))
        waitFor("late request read") { t.receives.get() > 51 }
        assertEquals("still inside the same second", 20, t.sent.size)
        now.addAndGet(1)
        t.deliver(DiscoveryCodec.request("later", "phone", "0123abce"))
        waitFor("one more reply") { t.sent.size == 21 }
        assertTrue(t.sent.last().text.contains("\"0123abce\""))
    }

    @Test
    fun transportErrorsKeepTheLoopAlive() { // [B2] analogue: "connection reset" etc. must not end the thread
        val t = FakeUdp()
        val logged = mutableListOf<String>()
        val responder = keep(DiscoveryResponder(t, { tv }, log = { synchronized(logged) { logged += it } }))
        responder.start()
        t.fail(SocketException("Connection reset"))
        t.fail(SocketException("Connection reset"))
        t.fail(IOException("Network is unreachable"))
        t.fail(IllegalStateException("anything else"))
        t.deliver(DiscoveryCodec.request("p", "phone", "0123abcd"))
        waitFor("reply after the errors") { t.sent.size == 1 }
        assertTrue(t.sent[0].text.contains("\"device_type\":\"tv\""))
        assertEquals(InetAddress.getByName("192.168.1.20"), t.sent[0].to)
        assertEquals(40000, t.sent[0].port)
        // a send that fails is survived too
        val failing = FakeUdp { _, _, _ -> throw SocketException("Connection reset by peer") }
        val r2 = keep(DiscoveryResponder(failing, { tv }))
        r2.start()
        failing.deliver(DiscoveryCodec.request("p", "phone", "0123abcd"))
        failing.deliver(DiscoveryCodec.request("p", "phone", "0123abce"))
        waitFor("both requests handled") { failing.sent.size == 2 }
        synchronized(logged) { assertEquals("one log line per kind of error", 3, logged.size) }
    }

    @Test
    fun closeEndsTheThread() {
        val t = keep(DatagramTransport(0, broadcast = false))
        val responder = DiscoveryResponder(t, { tv })
        responder.start()
        responder.close()
        waitFor("thread gone", 3_000) { Thread.getAllStackTraces().keys.none { it.name == "ferry-discovery" && it.isAlive } }
    }
}
