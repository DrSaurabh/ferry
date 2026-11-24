package app.ferry.core.log

import app.ferry.core.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.TimeZone

/** `LogReader` (2.1 plan §1.2, M8): cursor in the rotated file, gap line, 512 KB newline-aligned chunks. */
class LogReaderTest {
    private val dir: File = Files.createTempDirectory("logreader").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun log(max: Long) = EventLog(
        dir, Clock { 1_764_000_000_000L }, LogStateStore(File(dir, EventLog.STATE_FILE_NAME)),
        maxFileBytes = max, zone = TimeZone.getTimeZone("UTC"), session = "7f3a",
    )

    private fun seqs(body: String) = body.lines().filter { it.isNotEmpty() }.map { EventLog.seqOf(it) }

    @Test
    fun emptyWhenNothingNew() {
        val l = log(1_000_000)
        l.event("a")
        val c = LogReader(dir).linesAfter(1)
        assertTrue(c.isEmpty)
        assertNull(c.fromSeq)
        assertTrue(LogReader(File(dir, "none")).linesAfter(0).isEmpty)
    }

    @Test
    fun cursorInRotatedFile() {
        val l = log(4_000)
        repeat(100) { l.event("tap", "id" to "button$it", "pad" to "x".repeat(40)) }
        assertTrue(File(dir, EventLog.OLD_FILE_NAME).isFile)
        val oldFirst = EventLog.seqOf(File(dir, EventLog.OLD_FILE_NAME).readLines().first())!!
        val c = LogReader(dir).linesAfter(oldFirst + 2)
        assertEquals(oldFirst + 3, c.fromSeq)
        assertEquals(100L, c.lastSeq)
        assertEquals(((oldFirst + 3)..100L).toList(), seqs(c.body))
        assertTrue(c.body.endsWith("\n"))
    }

    @Test
    fun gapLineWhenCursorFellOffRotation() {
        val l = log(4_000)
        repeat(100) { l.event("tap", "id" to "button$it", "pad" to "x".repeat(40)) }
        val oldFirst = EventLog.seqOf(File(dir, EventLog.OLD_FILE_NAME).readLines().first())!!
        val c = LogReader(dir).linesAfter(3)
        val first = c.body.lines().first()
        assertTrue(first, first.endsWith(" #- s=---- gap from=4 to=${oldFirst - 1}"))
        assertEquals(oldFirst, c.fromSeq) // X-Ferry-Log-From = first real seq
        assertEquals(oldFirst, EventLog.seqOf(c.body.lines()[1]))
    }

    @Test
    fun chunksAreNewlineAlignedAndBounded() {
        val l = log(10_000_000)
        repeat(6_000) { l.event("diag", "tag" to "t", "msg" to "m$it " + "y".repeat(150)) }
        var cursor = 0L
        var total = 0
        var rounds = 0
        while (true) {
            val c = LogReader(dir).linesAfter(cursor)
            if (c.isEmpty) break
            rounds++
            assertTrue(c.body.toByteArray(Charsets.UTF_8).size <= LogReader.MAX_BYTES)
            assertTrue(c.body.endsWith("\n"))
            assertEquals(cursor + 1, c.fromSeq)
            total += seqs(c.body).size
            cursor = c.lastSeq!!
        }
        assertEquals(6_000, total)
        assertTrue("rounds $rounds", rounds >= 2)
    }
}
