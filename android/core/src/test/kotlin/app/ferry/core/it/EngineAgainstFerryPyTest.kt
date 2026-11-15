package app.ferry.core.it

import app.ferry.core.BytesSource
import app.ferry.core.Opened
import app.ferry.core.SystemClock
import app.ferry.core.net.FerryApi
import app.ferry.core.net.FerryClient
import app.ferry.core.net.FerryHttp
import app.ferry.core.sha256
import app.ferry.core.upload.EngineConfig
import app.ferry.core.upload.EngineState
import app.ferry.core.upload.ItemStatus
import app.ferry.core.upload.OpenedSource
import app.ferry.core.upload.Phase
import app.ferry.core.upload.SourceMeta
import app.ferry.core.upload.StopReason
import app.ferry.core.upload.Summary
import app.ferry.core.upload.UploadEngine
import app.ferry.core.upload.UploadItem
import app.ferry.core.upload.UploadSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * `UploadEngine` + `FerryClient` against the real ferry.py (plan §3.7 integration, Ph2b), on ubuntu and windows [B14].
 * Network trouble is made with [TcpRelay] [PB8].
 */
class EngineAgainstFerryPyTest {
    private val pcs = mutableListOf<FerryPcProcess>()
    private val relays = mutableListOf<TcpRelay>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val ids = AtomicLong()
    private val mtime = 1_650_000_000_000L

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        relays.forEach { it.close() }
        pcs.forEach { it.close() }
    }

    private fun pc(freeBytes: Long? = null) = FerryPcProcess.start(freeBytes = freeBytes).also { pcs += it }

    private fun relay(pc: FerryPcProcess) = TcpRelay(pc.base).also { relays += it }

    /** OkHttp client that counts `POST /api/v1/upload` requests [PB12]. */
    private class Counting(builder: (OkHttpClient.Builder) -> Unit = {}) {
        val uploads = AtomicInteger()
        val http: OkHttpClient = FerryHttp.newClient(null, "JVM engine test").newBuilder().also(builder)
            .addInterceptor { chain ->
                val r = chain.request()
                if (r.method == "POST" && r.url.encodedPath == "/api/v1/upload") uploads.incrementAndGet()
                chain.proceed(r)
            }
            .build()
    }

    private fun engine(client: FerryApi, config: EngineConfig = EngineConfig()): UploadEngine {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return UploadEngine(scope, { client }, { null }, SystemClock, config, log = { println("[engine ${System.currentTimeMillis() % 100_000}] $it") })
    }

    private fun item(name: String, source: UploadSource, size: Long, skipPrecheck: Boolean = false) =
        UploadItem(ids.incrementAndGet(), source, name, size, mtime, skipPrecheck = skipPrecheck)

    private fun item(name: String, bytes: ByteArray) = item(name, BytesSource(bytes, name), bytes.size.toLong())

    private fun finish(engine: UploadEngine, seconds: Long = 60): Summary =
        runBlocking { withTimeout(seconds * 1000) { engine.awaitFinished() } }

    private fun statuses(engine: UploadEngine) = engine.state.value.items.associate { it.name to it.status }

    private fun temps(pc: FerryPcProcess) = (pc.inbox.list() ?: emptyArray()).filter { it.startsWith(".ferry-") }

    private fun files(pc: FerryPcProcess) = (pc.inbox.list() ?: emptyArray()).filter { !it.startsWith(".ferry-") }.sorted()

    /** ferry.py deletes an aborted upload's temp file when it notices the closed socket. */
    private fun assertNoTempWithin(pc: FerryPcProcess, ms: Long) {
        val deadline = System.currentTimeMillis() + ms
        while (temps(pc).isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals("temp files left in the inbox", emptyList<String>(), temps(pc))
    }

    /** Records every phase the engine publishes. */
    private fun phases(engine: UploadEngine): MutableList<Phase> {
        val seen = Collections.synchronizedList(mutableListOf<Phase>())
        CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scopes += it }.launch {
            engine.state.collect { s -> if (seen.lastOrNull() != s.phase) seen += s.phase }
        }
        return seen
    }

    private fun awaitState(engine: UploadEngine, seconds: Long = 30, pred: (EngineState) -> Boolean): EngineState =
        runBlocking { withTimeout(seconds * 1000) { engine.state.first(pred) } }

    /** Bytes with a hook before every read (pace, gates). */
    private class HookedStream(private val inner: InputStream, private val before: (pos: Long) -> Unit) : InputStream() {
        private var pos = 0L
        override fun read(): Int {
            before(pos)
            return inner.read().also { if (it >= 0) pos++ }
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            before(pos)
            return inner.read(b, off, len).also { if (it > 0) pos += it }
        }
        override fun close() = inner.close()
    }

    private class HookedSource(
        private val bytes: ByteArray,
        override val displayName: String,
        private val before: (attempt: Int, pos: Long) -> Unit,
    ) : UploadSource {
        val opens = AtomicInteger()
        override fun open(): OpenedSource {
            val attempt = opens.incrementAndGet()
            return Opened(HookedStream(ByteArrayInputStream(bytes)) { before(attempt, it) }, bytes.size.toLong())
        }
        override fun refresh() = SourceMeta(bytes.size.toLong(), null)
    }

    /** Deterministic pseudo-random bytes of any length, generated on the fly (no 100 MB arrays). */
    private class GenStream(private val size: Long, seed: Long, private val pace: (Long) -> Unit = {}) : InputStream() {
        private var x = seed or 1L
        private var pos = 0L
        private fun next(): Int {
            x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17)
            return (x and 0xff).toInt()
        }
        override fun read(): Int = if (pos >= size) -1 else next().also { pos++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= size) return -1
            pace(pos)
            val n = minOf(len.toLong(), size - pos).toInt()
            for (i in 0 until n) b[off + i] = next().toByte()
            pos += n
            return n
        }
    }

    private class GenSource(val size: Long, val seed: Long, private val pace: (Long) -> Unit = {}) : UploadSource {
        override val displayName = "gen"
        override fun open(): OpenedSource = Opened(GenStream(size, seed, pace), size)
        override fun refresh() = SourceMeta(size, null)
        fun sha256(): String {
            val md = MessageDigest.getInstance("SHA-256")
            val s = GenStream(size, seed)
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = s.read(buf, 0, buf.size)
                if (n < 0) break
                md.update(buf, 0, n)
            }
            return app.ferry.core.util.Hex.encode(md.digest())
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun engineEndToEndSmallBatch() {
        val pc = pc()
        val counting = Counting()
        val client = FerryClient(counting.http, pc.base)
        val rnd = Random(10)
        val files = (1..30).map { i -> "IMG_%04d.jpg".format(i) to rnd.nextBytes(rnd.nextInt(1_000, 300_001)) }
        val e = engine(client)
        e.add(files.map { (n, b) -> item(n, b) })
        val s = finish(e)
        assertEquals(30, s.saved)
        assertEquals(files.map { it.first }.sorted(), files(pc))
        for ((n, b) in files) assertEquals(n, sha256(b), sha256(File(pc.inbox, n).readBytes()))
        assertEquals(30, counting.uploads.get())

        // Resend: all already on the PC, and not one upload request [B3]
        val e2 = engine(client)
        e2.add(files.map { (n, b) -> item(n, b) })
        val s2 = finish(e2)
        assertEquals(30, s2.alreadyOnPc)
        assertEquals(0, s2.saved)
        assertEquals("no upload request on resend", 30, counting.uploads.get())
        val lines = pc.awaitLogUpTo().filter { it.contains("/api/v1/upload") } // second witness [PB12]
        assertEquals(30, lines.size)
    }

    @Test
    fun growingFileIsNeverSavedTruncated() { // [PB1]
        val pc = pc()
        val n = 600_000 // several 256 KB chunks
        val bytes = Random(11).nextBytes(n + 1)
        val opens = AtomicInteger()
        val source = object : UploadSource {
            override val displayName = "grow.jpg"
            // first attempt: says N, yields N + 1 (still being written); afterwards stable at N + 1
            override fun open(): OpenedSource =
                Opened(ByteArrayInputStream(bytes), if (opens.incrementAndGet() == 1) n.toLong() else n + 1L)
            override fun refresh() = SourceMeta(n + 1L, mtime)
        }
        val e = engine(FerryClient(FerryHttp.newClient(null, "JVM engine test"), pc.base))
        e.add(listOf(item("grow.jpg", source, n.toLong())))
        val s = finish(e)
        assertEquals(ItemStatus.Saved("grow.jpg", false), statuses(e)["grow.jpg"])
        assertEquals(1, s.saved)
        assertEquals(2, opens.get())
        assertNoTempWithin(pc, 2_000)
        assertEquals("exactly one file, no truncated copy, no (1)", listOf("grow.jpg"), files(pc))
        assertArrayEquals(bytes, File(pc.inbox, "grow.jpg").readBytes())
    }

    /** Both streams stop at [at] bytes until the other has got there too, so the two uploads overlap. */
    private fun overlapping(a: ByteArray, an: String, b: ByteArray, bn: String, at: Long): List<UploadItem> {
        val bothThere = CountDownLatch(2)
        fun gated(bytes: ByteArray, name: String): UploadItem {
            val passed = java.util.concurrent.atomic.AtomicBoolean()
            val src = HookedSource(bytes, name) { attempt, pos ->
                if (attempt == 1 && pos >= at && passed.compareAndSet(false, true)) {
                    bothThere.countDown()
                    bothThere.await(20, TimeUnit.SECONDS)
                }
            }
            return item(name, src, bytes.size.toLong())
        }
        return listOf(gated(a, an), gated(b, bn))
    }

    @Test
    fun parallelCaseClashDifferentContent() { // [B14]
        val pc = pc()
        val a = Random(12).nextBytes(1_048_576)
        val b = Random(13).nextBytes(1_048_576)
        val e = engine(FerryClient(FerryHttp.newClient(null, "JVM engine test"), pc.base))
        e.add(overlapping(a, "IMG_0001.jpg", b, "img_0001.JPG", 512 * 1024))
        val s = finish(e)
        assertEquals(2, s.saved)
        val names = files(pc)
        println("case clash → $names (${System.getProperty("os.name")})")
        assertEquals(2, names.size)
        assertEquals(setOf(sha256(a), sha256(b)), names.map { sha256(File(pc.inbox, it).readBytes()) }.toSet())
        assertNoTempWithin(pc, 1_000)
    }

    @Test
    fun parallelIdenticalContent() { // [B14]
        val pc = pc()
        val a = Random(14).nextBytes(1_048_576)
        val e = engine(FerryClient(FerryHttp.newClient(null, "JVM engine test"), pc.base))
        e.add(overlapping(a, "a.jpg", a, "a.jpg", 512 * 1024))
        val s = finish(e)
        assertEquals(listOf("a.jpg"), files(pc))
        assertArrayEquals(a, File(pc.inbox, "a.jpg").readBytes())
        assertEquals(1, s.saved)
        assertEquals(1, s.alreadyOnPc) // the second reply was "skipped"
        assertNoTempWithin(pc, 1_000)
    }

    @Test
    fun diskFullLargeUpload() { // [B11]
        val pc = pc(freeBytes = 10L shl 20)
        val counting = Counting()
        val e = engine(FerryClient(counting.http, pc.base))
        // skipPrecheck ("Send anyway"): the pre-check would stop it before sending [B3]; this test is about the
        // upload itself, where the PC answers 507 after draining ≤ 64 MB, or closes before the 507 is read.
        e.add(listOf(item("big.mp4", GenSource(100L shl 20, 15), 100L shl 20, skipPrecheck = true)))
        val s = finish(e, 120)
        assertEquals(StopReason.DiskFull(null), s.stop)
        assertEquals(ItemStatus.NotSent(StopReason.DiskFull(null)), statuses(e)["big.mp4"])
        assertEquals("no retry loop", 1, counting.uploads.get())
        assertNoTempWithin(pc, 2_000)
        assertEquals(emptyList<String>(), files(pc))
    }

    @Test
    fun checkBatchSaysNotEnoughSpace() { // [B3]
        val pc = pc(freeBytes = 10L shl 20) // check-batch free = max(0, 10 MB − 64 MB) = 0
        val counting = Counting()
        val e = engine(FerryClient(counting.http, pc.base))
        e.add((1..3).map { item("new$it.jpg", Random(it).nextBytes(100_000)) })
        val s = finish(e)
        assertEquals(StopReason.DiskFull(300_000), s.stop)
        assertEquals(3, s.notSent)
        assertEquals(0, counting.uploads.get())
        assertEquals(emptyList<String>(), files(pc))
    }

    @Test
    fun cancelMidUploadLeavesNoTemp() {
        val pc = pc()
        val size = 40L shl 20
        val e = engine(FerryClient(FerryHttp.newClient(null, "JVM engine test"), pc.base))
        e.add(listOf(item("movie.mp4", GenSource(size, 16) { Thread.sleep(25) }, size))) // ~4 s
        awaitState(e) { st -> st.items.any { val x = it.status; x is ItemStatus.Sending && x.sent >= size / 2 } }
        e.cancel()
        val s = finish(e)
        assertEquals(1, s.cancelled)
        Thread.sleep(1_000)
        assertEquals("temp files 1 s after cancel", emptyList<String>(), temps(pc))
        assertEquals(emptyList<String>(), files(pc))
    }

    private fun pacedBatch(count: Int): List<Pair<String, ByteArray>> {
        val rnd = Random(17)
        return (1..count).map { "P_%03d.jpg".format(it) to rnd.nextBytes(200_000) }
    }

    private fun pacedItems(batch: List<Pair<String, ByteArray>>) =
        batch.map { (n, b) -> item(n, HookedSource(b, n) { _, _ -> Thread.sleep(150) }, b.size.toLong()) }

    private fun assertBatchSavedOnce(pc: FerryPcProcess, e: UploadEngine, batch: List<Pair<String, ByteArray>>) {
        val st = statuses(e)
        for ((n, _) in batch) assertTrue("$n: ${st[n]}", st[n] is ItemStatus.Saved)
        assertEquals("each file exactly once", batch.map { it.first }.sorted(), files(pc))
        for ((n, b) in batch) assertEquals(n, sha256(b), sha256(File(pc.inbox, n).readBytes()))
    }

    @Test
    fun pcGoesAwayAndComesBack() { // [PB8]
        val pc = pc()
        val relay = relay(pc)
        val e = engine(FerryClient(FerryHttp.newClient(null, "JVM engine test"), relay.base))
        val seen = phases(e)
        val batch = pacedBatch(20)
        val t0 = System.currentTimeMillis()
        e.add(pacedItems(batch))
        awaitState(e) { st -> st.items.count { it.status is ItemStatus.Saved } >= 4 }
        relay.mode = TcpRelay.Mode.RESET
        Thread.sleep(8_000)
        relay.mode = TcpRelay.Mode.PASS
        val s = finish(e)
        println("pcGoesAwayAndComesBack: ${System.currentTimeMillis() - t0} ms, phases $seen, $s")
        assertTrue("never showed WaitingForPc: $seen", Phase.WaitingForPc in seen)
        assertEquals(20, s.saved)
        assertBatchSavedOnce(pc, e, batch)
        assertTrue(System.currentTimeMillis() - t0 <= 60_000)
    }

    @Test
    fun silentStallIsNoticedAndRecovered() { // [PB8] [PB11] upload half (the download half is Ph3a)
        val pc = pc()
        val relay = relay(pc)
        val http = FerryHttp.newClient(null, "JVM engine test").newBuilder()
            .writeTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()
        val client = FerryClient(http, relay.base, uploadReadTimeoutMs = 3_000)
        val e = engine(client)
        val seen = phases(e)
        val bytes = Random(18).nextBytes(4 shl 20)
        val atGate = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = HookedSource(bytes, "stall.mp4") { attempt, pos ->
            if (attempt == 1 && pos >= (1 shl 20)) {
                atGate.countDown()
                release.await(20, TimeUnit.SECONDS)
            }
        }
        e.add(listOf(item("stall.mp4", source, bytes.size.toLong())))
        assertTrue(atGate.await(20, TimeUnit.SECONDS))
        relay.mode = TcpRelay.Mode.BLACKHOLE // no RST, nothing forwarded any more
        release.countDown()
        awaitState(e, 60) { it.phase == Phase.WaitingForPc }
        relay.mode = TcpRelay.Mode.PASS
        val s = finish(e)
        println("silentStall: phases $seen, $s, opens ${source.opens.get()}")
        assertEquals(1, s.saved)
        assertTrue(source.opens.get() >= 2)
        assertEquals(ItemStatus.Saved("stall.mp4", false), statuses(e)["stall.mp4"])
        assertEquals(listOf("stall.mp4"), files(pc))
        assertArrayEquals(bytes, File(pc.inbox, "stall.mp4").readBytes())
        assertNoTempWithin(pc, 2_000)
    }

    @Test
    fun pcRestartOnSamePort() { // [PB8] [PR16] Linux only: Windows binds with SO_EXCLUSIVEADDRUSE
        Assume.assumeFalse("Linux only", System.getProperty("os.name").startsWith("Windows"))
        val pc = pc()
        val e = engine(FerryClient(FerryHttp.newClient(null, "JVM engine test"), pc.base))
        val seen = phases(e)
        val batch = pacedBatch(20)
        e.add(pacedItems(batch))
        awaitState(e) { st -> st.items.count { it.status is ItemStatus.Saved } >= 4 }
        val pc2 = pc.restartOnSamePort().also { pcs += it }
        assertEquals(pc.base, pc2.base)
        val s = finish(e)
        println("pcRestartOnSamePort: phases $seen, $s")
        assertEquals(20, s.saved)
        assertBatchSavedOnce(pc2, e, batch)
    }
}
