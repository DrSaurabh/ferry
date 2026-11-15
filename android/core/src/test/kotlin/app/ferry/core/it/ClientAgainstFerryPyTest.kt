package app.ferry.core.it

import app.ferry.core.HostPort
import app.ferry.core.itemOf
import app.ferry.core.model.CheckFile
import app.ferry.core.net.FerryClient
import app.ferry.core.net.FerryException
import app.ferry.core.net.FerryHttp
import app.ferry.core.sha256
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import kotlin.random.Random

/** `FerryClient` against the real ferry.py (plan §3.7 integration; run on ubuntu and windows [B14]). */
class ClientAgainstFerryPyTest {
    private val pcs = mutableListOf<FerryPcProcess>()

    private fun pc(freeBytes: Long? = null) = FerryPcProcess.start(freeBytes = freeBytes).also { pcs += it }

    private fun client(pc: FerryPcProcess, device: String = "JVM test") = FerryClient(FerryHttp.newClient(null, device), pc.base)

    @After
    fun tearDown() = pcs.forEach { it.close() }

    private fun inboxFiles(pc: FerryPcProcess): List<String> = (pc.inbox.list() ?: emptyArray()).sorted()

    @Test
    fun infoAnswers(): Unit = runBlocking {
        val pc = pc()
        val info = client(pc).info()
        assertEquals("ferry", info.app)
        assertEquals(2, info.protocol)
        assertTrue(info.capabilities.contains("check_batch"))
    }

    @Test
    fun uploadIsByteIdentical(): Unit = runBlocking {
        val pc = pc()
        val bytes = Random(1).nextBytes(5 * 1024 * 1024)
        var last = 0L
        val reply = client(pc).upload(itemOf(bytes, "IMG_0001.jpg"), app.ferry.core.BytesSource(bytes).open()) { last = it }
        assertEquals("saved", reply.result)
        assertEquals("IMG_0001.jpg", reply.savedAs)
        assertEquals(bytes.size.toLong(), last)
        val f = File(pc.inbox, "IMG_0001.jpg")
        assertEquals(sha256(bytes), sha256(f.readBytes()))
        // Names with '+', '%', spaces and non-ASCII arrive unchanged (query is percent-encoded UTF-8).
        val odd = "Holiday + café 100%.jpg"
        val small = "hello".toByteArray()
        assertEquals(odd, client(pc).upload(itemOf(small, odd), app.ferry.core.BytesSource(small).open()) {}.savedAs)
        assertArrayEquals(small, File(pc.inbox, odd).readBytes())
    }

    @Test
    fun mtimeRestored(): Unit = runBlocking {
        val pc = pc()
        val bytes = Random(2).nextBytes(10_000)
        val mtime = 1_600_000_000_000L
        val reply = client(pc).upload(itemOf(bytes, "a.jpg", mtime), app.ferry.core.BytesSource(bytes).open()) {}
        assertEquals("saved", reply.result)
        val diff = Math.abs(File(pc.inbox, "a.jpg").lastModified() - mtime)
        assertTrue("mtime off by $diff ms", diff <= 2_000)
    }

    @Test
    fun mtimeOmittedWhenUnknown(): Unit = runBlocking { // [B17]
        val pc = pc()
        val bytes = Random(3).nextBytes(20_000)
        val c = client(pc)
        assertEquals("saved", c.upload(itemOf(bytes, "b.jpg", null), app.ferry.core.BytesSource(bytes).open()) {}.result)
        val again = c.upload(itemOf(bytes, "b.jpg", null), app.ferry.core.BytesSource(bytes).open()) {}
        assertEquals("skipped", again.result)
        assertEquals("b.jpg", again.savedAs)
        assertEquals(listOf("b.jpg"), inboxFiles(pc))
        val lines = pc.awaitLogUpTo().filter { it.contains("/api/v1/upload") }
        assertEquals(2, lines.size)
        assertTrue(lines.toString(), lines.none { it.contains("mtime") })
    }

    @Test
    fun renameOnDifferentContent(): Unit = runBlocking {
        val pc = pc()
        val a = Random(4).nextBytes(30_000)
        val b = Random(5).nextBytes(30_000)
        val c = client(pc)
        assertEquals("saved", c.upload(itemOf(a, "x.jpg"), app.ferry.core.BytesSource(a).open()) {}.result)
        val r = c.upload(itemOf(b, "x.jpg"), app.ferry.core.BytesSource(b).open()) {}
        assertEquals("renamed", r.result)
        assertEquals("x (1).jpg", r.savedAs)
        assertArrayEquals(a, File(pc.inbox, "x.jpg").readBytes())
        assertArrayEquals(b, File(pc.inbox, "x (1).jpg").readBytes())
    }

    @Test
    fun checkBatchFindsDuplicates(): Unit = runBlocking { // [B3]
        val pc = pc()
        val bytes = Random(6).nextBytes(40_000)
        val mtime = 1_650_000_000_000L
        val c = client(pc)
        c.upload(itemOf(bytes, "dup.jpg", mtime), app.ferry.core.BytesSource(bytes).open()) {}
        val reply = c.checkBatch(
            listOf(
                CheckFile("dup.jpg", bytes.size.toLong(), mtime),
                CheckFile("new.jpg", 7_000, 1_650_000_000_000L),
                CheckFile("nodate.jpg", 3_000, null),
            ),
        )
        assertEquals("dup.jpg", reply.results[0].duplicate)
        assertNull(reply.results[1].duplicate)
        assertNull(reply.results[2].duplicate)
        assertEquals(10_000L, reply.neededBytes)
        assertTrue((reply.freeBytes ?: 0) > 0)
    }

    @Test
    fun deviceNameWithCurlyQuoteAndEmoji(): Unit = runBlocking { // [B10]
        val pc = pc()
        val name = "Saurabh’s phone 📱"
        val bytes = "hi".toByteArray()
        client(pc, name).upload(itemOf(bytes, "n.txt"), app.ferry.core.BytesSource(bytes).open()) {}
        val lines = pc.awaitLogUpTo()
        assertTrue(lines.joinToString("\n"), lines.any { it.contains("/api/v1/upload") && it.split('\t').contains(name) })
    }

    @Test
    fun diskFullSmallUpload(): Unit = runBlocking {
        val pc = pc(freeBytes = 10L * 1024 * 1024)
        val bytes = Random(7).nextBytes(1024 * 1024)
        try {
            client(pc).upload(itemOf(bytes, "big.bin"), app.ferry.core.BytesSource(bytes).open()) {}
            fail("expected DiskFull")
        } catch (e: FerryException.DiskFull) {
            // expected
        }
        assertEquals(emptyList<String>(), inboxFiles(pc))
    }

    @Test
    fun closedPortIsUnreachable(): Unit = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        try {
            FerryClient(FerryHttp.newClient(null, "JVM test"), HostPort("127.0.0.1", port)).info()
            fail("expected Unreachable")
        } catch (e: FerryException.Unreachable) {
            // expected
        }
    }
}
