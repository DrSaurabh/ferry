package app.ferry.core.it

import app.ferry.core.SystemClock
import app.ferry.core.download.DownloadQueue
import app.ferry.core.download.DownloadStatus
import app.ferry.core.download.FileSink
import app.ferry.core.model.AppInfo
import app.ferry.core.model.OutFile
import app.ferry.core.net.FerryClient
import app.ferry.core.net.FerryException
import app.ferry.core.net.FerryHttp
import app.ferry.core.sha256
import app.ferry.core.update.AppUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Outbox downloads and the app update against the real ferry.py (plan §3.7 integration, Ph3a), on ubuntu and
 * windows; the download half of `silentStallIsNoticedAndRecovered` [PB8] [PB11] through [TcpRelay].
 */
class DownloadAgainstFerryPyTest {
    private val pcs = mutableListOf<FerryPcProcess>()
    private val relays = mutableListOf<TcpRelay>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val dir: File = Files.createTempDirectory("ferry-dl").toFile()
    private val os = System.getProperty("os.name")

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        relays.forEach { it.close() }
        pcs.forEach { it.close() }
        dir.deleteRecursively()
    }

    private fun client(pc: FerryPcProcess) = FerryClient(FerryHttp.newClient(null, "JVM download test"), pc.base)

    private fun note(text: String) = println("FERRY_NOTE $text")

    /** Puts a file into out/ and waits until the PC offers it (2 s stability rule). */
    private fun offer(pc: FerryPcProcess, api: FerryClient, name: String, bytes: ByteArray): OutFile {
        File(pc.outbox, name).writeBytes(bytes)
        return runBlocking {
            withTimeout(20_000) {
                var f: OutFile? = null
                while (f == null) {
                    f = api.outbox().files.firstOrNull { it.name == name }
                    if (f == null) delay(250)
                }
                f!!
            }
        }
    }

    private fun parts() = dir.walk().filter { it.isFile && it.name.endsWith(".part") }.map { it.name }.toList()

    private fun queue(api: FerryClient, log: MutableList<String>): DownloadQueue {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return DownloadQueue(scope, { api }, { null }, SystemClock, log = {
            log += it
            println("[queue ${System.currentTimeMillis() % 100_000}] $it")
        })
    }

    private fun awaitEnd(q: DownloadQueue, id: Int, seconds: Long = 60): DownloadStatus? = runBlocking {
        withTimeout(seconds * 1000) {
            q.state.first { val s = it[id]; s is DownloadStatus.Done || s is DownloadStatus.Failed || s is DownloadStatus.Cancelled }[id]
        }
    }

    @Test
    fun outboxListAndDownload() {
        val pc = FerryPcProcess.start().also { pcs += it }
        val api = client(pc)
        val bytes = Random(31).nextBytes(3 shl 20)
        val name = "Holiday + café 100%.jpg"
        val f = offer(pc, api, name, bytes)
        assertEquals(bytes.size.toLong(), f.size)
        assertEquals("image", f.kind)
        assertTrue(f.url, f.url.startsWith("/api/v1/outbox/${f.id}/"))

        // straight through FerryClient
        val target = File(dir, "direct/$name")
        val saved = runBlocking { api.download(f.url, f.size, FileSink(target)) {} }
        assertEquals(name, saved)
        assertArrayEquals(bytes, target.readBytes())

        // truncated: the expected size is wrong → aborted, nothing committed [B7]
        val wrong = File(dir, "wrong/$name")
        try {
            runBlocking { api.download(f.url, f.size + 1, FileSink(wrong)) {} }
            fail("expected BadReply")
        } catch (e: FerryException.BadReply) {
            // expected
        }
        assertFalse(wrong.exists())

        // through the queue
        val log = Collections.synchronizedList(mutableListOf<String>())
        val q = queue(api, log)
        val viaQueue = File(dir, "queue/$name")
        q.enqueue(f) { FileSink(viaQueue) }
        assertEquals(DownloadStatus.Done(name, viaQueue.path), awaitEnd(q, f.id))
        assertArrayEquals(bytes, viaQueue.readBytes())
        assertEquals(emptyList<String>(), parts())
    }

    @Test
    fun appInfoAndUpdateDownload() {
        val appDir = File(dir, "app").apply { mkdirs() }
        val apk = Random(57).nextBytes(400_000)
        val apkFile = File(appDir, "Ferry.apk").apply { writeBytes(apk) }
        val json = File(appDir, "Ferry.apk.json")
        fun writeJson(sha: String) = json.writeText(
            """{"version_code": 20057, "version_name": "2.0.57", "size": ${apk.size}, "sha256": "$sha", "url": "Ferry.apk"}""",
        )
        writeJson(sha256(apk))
        val pc = FerryPcProcess.start(appDir = appDir).also { pcs += it }
        val api = client(pc)

        val info = runBlocking { api.appInfo() }!!
        assertEquals(AppInfo(20057, "2.0.57", apk.size.toLong(), sha256(apk), info.url), info)
        assertTrue(info.url, info.url.startsWith("/app"))
        assertTrue(AppUpdate.isNewer(info, 20056))

        val target = File(dir, "update/Ferry.apk")
        runBlocking { AppUpdate.downloadApk(api, info, target) {} }
        assertArrayEquals(apk, target.readBytes())

        // a hash that does not match what arrives → nothing kept
        try {
            runBlocking { AppUpdate.downloadApk(api, info.copy(sha256 = sha256(apk + byteArrayOf(1))), target) {} }
            fail("expected BadReply")
        } catch (e: FerryException.BadReply) {
            // expected
        }
        assertFalse(target.exists())
        assertEquals(emptyList<String>(), parts())

        // a mismatched pair on the PC (json says another sha) → 404 → null [B26]
        writeJson(sha256(apk + byteArrayOf(2)))
        assertNull(runBlocking { api.appInfo() })
        assertTrue(apkFile.exists())
    }

    /** FileSink that can stop in the middle of attempt 1 (the test then stalls the relay) and records its fate. */
    private class GateSink(target: File, private val gateAt: Long?, val atGate: CountDownLatch, val release: CountDownLatch) :
        FileSink(target) {
        @Volatile var aborted = false
        @Volatile var committed = false
        @Volatile var written = 0L

        override fun open(): OutputStream {
            val o = super.open()
            return object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (gateAt != null && written >= gateAt && atGate.count > 0) {
                        atGate.countDown()
                        release.await(20, TimeUnit.SECONDS)
                    }
                    o.write(b, off, len)
                    written += len
                }
                override fun close() = o.close()
            }
        }

        override fun commit(): String = super.commit().also { committed = true }

        override fun abort() {
            aborted = true
            super.abort()
        }
    }

    @Test
    fun silentStallIsNoticedAndRecovered() { // [PB8] [PB11] download half (the upload half is in EngineAgainstFerryPyTest)
        val pc = FerryPcProcess.start().also { pcs += it }
        val bytes = Random(48).nextBytes(48 shl 20) // far more than the loopback socket buffers can hold
        val f = offer(pc, client(pc), "stall.mp4", bytes)
        val relay = TcpRelay(pc.base).also { relays += it }
        val http = FerryHttp.newClient(null, "JVM download test").newBuilder()
            .writeTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()
        val api = FerryClient(http, relay.base)
        val log = Collections.synchronizedList(mutableListOf<String>())
        val q = queue(api, log)
        val target = File(dir, "stall/stall.mp4")
        val atGate = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sinks = Collections.synchronizedList(mutableListOf<GateSink>())
        q.enqueue(f) { GateSink(target, if (sinks.isEmpty()) 1L shl 20 else null, atGate, release).also { sinks += it } }

        assertTrue("first attempt never reached 1 MB", atGate.await(20, TimeUnit.SECONDS))
        relay.mode = TcpRelay.Mode.BLACKHOLE // no RST, no FIN, nothing forwarded any more
        release.countDown()
        val t0 = System.currentTimeMillis()
        runBlocking { withTimeout(60_000) { q.waitingForPc.first { it } } }
        val noticedMs = System.currentTimeMillis() - t0
        assertFalse(target.exists())
        relay.mode = TcpRelay.Mode.PASS
        val end = awaitEnd(q, f.id)
        note("silentStallIsNoticedAndRecovered (download) on $os: waiting after $noticedMs ms, attempts ${sinks.size}, " +
            "attempt 1 had ${sinks[0].written} bytes")
        assertEquals(DownloadStatus.Done("stall.mp4", target.path), end)
        assertEquals(2, sinks.size)
        assertTrue(sinks[0].aborted && !sinks[0].committed) // the partial attempt was never committed
        assertTrue(sinks[0].written in 1 until bytes.size)
        assertTrue(sinks[1].committed)
        assertArrayEquals(bytes, target.readBytes()) // restarted from zero
        assertEquals(emptyList<String>(), parts())
    }
}
