package app.ferry.core.it

import app.ferry.core.HostPort
import app.ferry.core.discovery.DatagramTransport
import app.ferry.core.discovery.DiscoveryClient
import app.ferry.core.discovery.LocateResult
import app.ferry.core.discovery.PcLocator
import app.ferry.core.discovery.PcStore
import app.ferry.core.discovery.UdpTransport
import app.ferry.core.net.FerryClient
import app.ferry.core.net.FerryHttp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/** Discovery and the locator against the real `pc/ferry.py` (plan §3.7, run on ubuntu and windows). */
class DiscoveryAgainstFerryPyTest {
    private val pcs = mutableListOf<FerryPcProcess>()
    private val http = FerryHttp.newClient(null, "JVM discovery test")
    private val loopback = InetAddress.getByName("127.0.0.1")

    @After
    fun tearDown() = pcs.forEach { it.close() }

    private class Store(override var lastKnown: HostPort? = null) : PcStore {
        override var manual: HostPort? = null
        override var chosenName: String? = null
    }

    private fun countingTransport(n: AtomicInteger): () -> UdpTransport = {
        n.incrementAndGet()
        DatagramTransport()
    }

    @Test
    fun discoveryAgainstFerryPy(): Unit = runBlocking {
        val pc = FerryPcProcess.start(discovery = true).also { pcs += it }
        assertNotNull("ferry.py discovery port", pc.discoveryPort)
        val udpPort = pc.discoveryPort!!
        val client = DiscoveryClient({ DatagramTransport() }, { listOf(loopback) }, "JVM phone" to "phone", udpPort)
        val t0 = System.nanoTime()
        val peers = client.discover()
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("FERRY_NOTE discoveryAgainstFerryPy: ${peers.size} peer(s) in $ms ms: $peers")
        val p = peers.single()
        assertEquals("pc", p.deviceType)
        assertEquals("127.0.0.1", p.host)
        assertEquals("reply carries the TCP port [B16]", pc.base.port, p.port)
        assertEquals(2, p.protocol)
        assertEquals("2.1.0", p.version)

        val store = Store()
        val locator = PcLocator({ FerryClient(http, it) }, client, store, log = { println("[locator] $it") })
        val r = locator.locate()
        assertTrue(r.toString(), r is LocateResult.Found)
        r as LocateResult.Found
        assertEquals(pc.base, r.api.base)
        assertEquals(p.name, r.info.name)
        assertEquals(pc.base, store.lastKnown)
        val log = pc.awaitLogUpTo()
        assertTrue("discovery event in ferry-log.txt", log.any { it.contains("discovery reply to 127.0.0.1") && it.contains("JVM phone") })
    }

    @Test
    fun locatorPrefersLastKnown(): Unit = runBlocking {
        val pc = FerryPcProcess.start(discovery = true).also { pcs += it }
        val sends = AtomicInteger()
        val client = DiscoveryClient(countingTransport(sends), { listOf(loopback) }, "JVM phone" to "phone", pc.discoveryPort!!)
        val store = Store(lastKnown = pc.base)
        val r = PcLocator({ FerryClient(http, it) }, client, store).locate()
        assertTrue(r.toString(), r is LocateResult.Found)
        assertEquals(pc.base, (r as LocateResult.Found).api.base)
        assertEquals("no UDP socket opened", 0, sends.get())
        assertTrue(pc.awaitLogUpTo().none { it.contains("discovery reply") })
    }
}
