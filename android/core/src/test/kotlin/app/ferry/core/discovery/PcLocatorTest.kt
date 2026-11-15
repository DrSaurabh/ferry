package app.ferry.core.discovery

import app.ferry.core.HostPort
import app.ferry.core.download.DownloadSink
import app.ferry.core.model.AppInfo
import app.ferry.core.model.CheckBatchReply
import app.ferry.core.model.CheckFile
import app.ferry.core.model.DiscoveryReply
import app.ferry.core.model.OutboxList
import app.ferry.core.model.PcInfo
import app.ferry.core.model.UploadReply
import app.ferry.core.net.FerryApi
import app.ferry.core.net.FerryException
import app.ferry.core.upload.OpenedSource
import app.ferry.core.upload.UploadItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** `PcLocator` with a fake PC per address and a fake LAN for the broadcast (plan §3.7, [PB22] [H35]). */
class PcLocatorTest {
    private sealed interface Pc
    private data class Alive(val name: String, val protocol: Int = 2) : Pc
    private object Dead : Pc

    /** Which address answers how; anything not listed is dead. */
    private val pcs = mutableMapOf<HostPort, Pc>()
    private val infoCalls: MutableList<Pair<HostPort, Long>> = Collections.synchronizedList(mutableListOf())

    /** Devices that answer the broadcast. */
    private val lan = mutableListOf<DiscoveryReply>()
    private val lanHosts = mutableMapOf<DiscoveryReply, String>()
    private val transports = AtomicInteger()
    private val udp = mutableListOf<FakeUdp>()

    private class Store(
        override var lastKnown: HostPort? = null,
        override var manual: HostPort? = null,
        override var chosenName: String? = null,
    ) : PcStore

    private inner class Api(override val base: HostPort) : FerryApi {
        override suspend fun info(timeoutMs: Long): PcInfo {
            infoCalls += base to timeoutMs
            return when (val p = pcs[base] ?: Dead) {
                Dead -> throw FerryException.Unreachable(ConnectException("fake: nobody at $base"))
                is Alive -> if (p.protocol < 2) throw FerryException.OldPc(p.protocol) else
                    PcInfo(app = "ferry", protocol = p.protocol, version = "2.0.0", name = p.name)
            }
        }

        override suspend fun checkBatch(files: List<CheckFile>): CheckBatchReply = throw UnsupportedOperationException()
        override suspend fun upload(item: UploadItem, opened: OpenedSource, onProgress: (Long) -> Unit): UploadReply =
            throw UnsupportedOperationException()
        override suspend fun outbox(): OutboxList = throw UnsupportedOperationException()
        override suspend fun appInfo(): AppInfo? = throw UnsupportedOperationException()
        override suspend fun download(urlPath: String, expectedSize: Long, sink: DownloadSink, onProgress: (Long) -> Unit): String =
            throw UnsupportedOperationException()
        override fun absoluteUrl(urlPath: String): String = "http://$base$urlPath"
    }

    private fun onLan(host: String, name: String, type: String = "pc", port: Int = 8765) {
        val r = DiscoveryReply(name = name, deviceType = type, port = port, version = "2.0.0")
        lan += r
        lanHosts[r] = host
    }

    private fun discovery() = DiscoveryClient(
        transport = {
            transports.incrementAndGet()
            FakeUdp { data, _, port ->
                assertEquals(DiscoveryCodec.PORT, port)
                val req = DiscoveryCodec.parseRequest(data, data.size)!!
                for (r in lan) deliver(DiscoveryCodec.reply(r.copy(nonce = req.nonce)), InetAddress.getByName(lanHosts[r]), 8765)
            }.also { udp += it }
        },
        targets = { listOf(InetAddress.getByName("255.255.255.255")) },
        self = "vivo X200 Pro" to "phone",
    )

    private fun locator(store: Store, withNetwork: Boolean = true) =
        PcLocator({ Api(it) }, if (withNetwork) discovery() else null, store, log = { println("[locator] $it") })

    private val home = HostPort("192.168.1.5")

    @Test
    fun lastKnownFirstAndNoUdpWhenItAnswers(): Unit = runBlocking {
        pcs[home] = Alive("SAURABH-PC")
        val store = Store(lastKnown = home)
        val r = locator(store).locate()
        assertTrue(r.toString(), r is LocateResult.Found)
        r as LocateResult.Found
        assertEquals("SAURABH-PC", r.info.name)
        assertEquals(home, r.api.base)
        assertEquals("no broadcast", 0, transports.get())
        assertEquals(listOf(home to 1_500L), infoCalls.toList())
    }

    @Test
    fun manualAnswersFirst(): Unit = runBlocking {
        val typed = HostPort("192.168.1.9", 8800)
        pcs[typed] = Alive("OTHER-PC")
        pcs[home] = Alive("SAURABH-PC")
        val store = Store(lastKnown = home, manual = typed)
        val r = locator(store).locate() as LocateResult.Found
        assertEquals(typed, r.api.base)
        assertEquals(typed, store.lastKnown)
        assertEquals(typed, store.manual)
        assertEquals(0, transports.get())
    }

    @Test
    fun manualDeadFallsThroughToBroadcast(): Unit = runBlocking { // [PB22] [H35]
        val typed = HostPort("192.168.1.9")
        val moved = HostPort("192.168.1.23", 8800)
        pcs[moved] = Alive("SAURABH-PC")
        onLan("192.168.1.23", "SAURABH-PC", port = 8800)
        val store = Store(lastKnown = home, manual = typed) // both old addresses dead
        val r = locator(store).locate()
        assertTrue(r.toString(), r is LocateResult.Found)
        assertEquals(moved, (r as LocateResult.Found).api.base)
        assertNull("dead typed address forgotten", store.manual)
        assertEquals(moved, store.lastKnown)
        assertEquals(listOf(typed, home, moved), infoCalls.map { it.first })
        assertEquals(1, transports.get())
        assertEquals("3 sends × 1 target", 3, udp.single().sent.size)
    }

    @Test
    fun manualKeptWhenNothingIsFound(): Unit = runBlocking {
        val typed = HostPort("192.168.1.9")
        val store = Store(manual = typed)
        val r = locator(store).locate()
        assertEquals(LocateResult.NotFound(emptyList()), r)
        assertEquals(typed, store.manual)
        assertNull(store.lastKnown)
    }

    @Test
    fun oldPcAtLastKnown(): Unit = runBlocking {
        pcs[home] = Alive("OLD-PC", protocol = 1)
        val store = Store(lastKnown = home)
        assertEquals(LocateResult.OldPc(home, 1), locator(store).locate())
        assertEquals(0, transports.get())
    }

    @Test
    fun oldPcFoundByBroadcastIsReportedButNotStored(): Unit = runBlocking {
        val old = HostPort("192.168.1.30")
        pcs[old] = Alive("OLD-PC", protocol = 1)
        onLan("192.168.1.30", "OLD-PC")
        val store = Store()
        assertEquals(LocateResult.OldPc(old, 1), locator(store).locate())
        assertNull(store.lastKnown)
    }

    @Test
    fun nothingAnswersIsNotFoundWithTheCandidates(): Unit = runBlocking {
        onLan("192.168.1.40", "DEAD-HTTP-PC") // answers UDP, but /info does not
        val store = Store(lastKnown = home)
        val r = locator(store).locate()
        assertTrue(r.toString(), r is LocateResult.NotFound)
        val c = (r as LocateResult.NotFound).candidates
        assertEquals(listOf("DEAD-HTTP-PC"), c.map { it.name })
        assertEquals("deduplicated across the 3 sends", 1, c.size)
        assertEquals(home, store.lastKnown)
    }

    @Test
    fun chosenPcIsPreferredAmongSeveral(): Unit = runBlocking {
        pcs[HostPort("192.168.1.50")] = Alive("OFFICE-PC")
        pcs[HostPort("192.168.1.51")] = Alive("SAURABH-PC")
        onLan("192.168.1.50", "OFFICE-PC")
        onLan("192.168.1.51", "SAURABH-PC")
        val first = locator(Store()).locate() as LocateResult.Found
        assertEquals("first that answers when nothing was chosen", "OFFICE-PC", first.info.name)
        val store = Store(chosenName = "SAURABH-PC")
        val r = locator(store).locate() as LocateResult.Found
        assertEquals("SAURABH-PC", r.info.name)
        assertEquals(HostPort("192.168.1.51"), store.lastKnown)
    }

    @Test
    fun tvsAreNeverTakenForThePcAndFindTvsListsThem(): Unit = runBlocking {
        pcs[HostPort("192.168.1.60", 8766)] = Alive("TV pretending")
        pcs[home] = Alive("SAURABH-PC")
        onLan("192.168.1.60", "Living room TV", type = "tv", port = 8766)
        onLan("192.168.1.5", "SAURABH-PC")
        val loc = locator(Store())
        val r = loc.locate() as LocateResult.Found
        assertEquals(home, r.api.base)
        val tvs = loc.findTvs()
        assertEquals(listOf("Living room TV"), tvs.map { it.name })
        assertEquals(8766, tvs.single().port)
    }

    @Test
    fun noNetworkWhenNothingStoredAnswersAndThereIsNoWifi(): Unit = runBlocking {
        val store = Store(lastKnown = home)
        assertEquals(LocateResult.NoNetwork, locator(store, withNetwork = false).locate())
        assertEquals(emptyList<Any>(), locator(store, withNetwork = false).findTvs())
        pcs[home] = Alive("SAURABH-PC")
        assertTrue(locator(store, withNetwork = false).locate() is LocateResult.Found)
    }

    @Test
    fun verifyStoresNothing(): Unit = runBlocking {
        pcs[home] = Alive("SAURABH-PC")
        val store = Store()
        val r = locator(store).verify(home)
        assertTrue(r is LocateResult.Found)
        assertNull(store.lastKnown)
        assertEquals(LocateResult.NotFound(emptyList()), locator(store).verify(HostPort("192.168.1.99")))
    }
}
