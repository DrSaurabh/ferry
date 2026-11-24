package app.ferry.core.log

import app.ferry.core.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.TimeZone

/** `EventLog` (2.1 plan §1.2, §2.2, CP109, CP133 [PS4], [PS3]). */
class EventLogTest {
    private val dir: File = Files.createTempDirectory("eventlog").toFile()
    private val utc = TimeZone.getTimeZone("UTC")
    private var now = 1_764_000_000_000L // 2025-11-24 16:00:00 UTC
    private val clock = Clock { now }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun store() = LogStateStore(File(dir, EventLog.STATE_FILE_NAME))

    private fun log(detail: Detail = Detail.Detailed, max: Long = EventLog.MAX_FILE_BYTES, session: String = "7f3a") =
        EventLog(dir, clock, store(), { detail }, maxFileBytes = max, zone = utc, session = session)

    private fun fileLines() = File(dir, EventLog.FILE_NAME).readLines(Charsets.UTF_8)

    @Test
    fun lineFormat() {
        val l = log()
        l.event("tap", "id" to "send", "screen" to "picker")
        assertEquals(listOf("2025-11-24 16:00:00.000 #1 s=7f3a tap id=send screen=picker"), fileLines())
        assertEquals(1L, l.lastSeq)
    }

    @Test
    fun quotingAndEscaping() {
        val l = log()
        l.event("err.shown", "text" to "PC not found — is Ferry running?", "empty" to "", "nul" to null,
            "q" to "say \"hi\"", "eq" to "a=b", "bs" to "c:\\x", "nl" to "one\ntwo")
        assertEquals(
            "2025-11-24 16:00:00.000 #1 s=7f3a err.shown text=\"PC not found — is Ferry running?\" empty=\"\" nul=\"\" " +
                "q=\"say \\\"hi\\\"\" eq=\"a=b\" bs=\"c:\\\\x\" nl=\"one\\ntwo\"",
            fileLines().single(),
        )
    }

    @Test
    fun longValueTruncatedTo2KB() {
        val l = log()
        l.event("diag", "tag" to "t", "msg" to "é".repeat(5_000))
        val line = fileLines().single()
        assertTrue(line.toByteArray(Charsets.UTF_8).size <= EventLog.MAX_LINE_BYTES)
        assertTrue(line.endsWith("…"))
        assertTrue(line.contains(" diag tag=t msg=éé"))
    }

    @Test
    fun badNamesAreMadeToFit() {
        val l = log()
        l.event("Send Start!", "Bad Key" to 1)
        assertTrue(fileLines().single(), fileLines().single().endsWith(" s=7f3a send_start_ bad_key=1"))
    }

    @Test
    fun seqContinuesAcrossRestart() {
        log().apply { event("a"); event("b"); saveState() }
        val second = log(session = "0b0b")
        second.event("c")
        assertEquals(listOf("#1", "#2", "#3"), fileLines().map { it.split(" ")[2] })
        assertTrue(fileLines().last().contains(" s=0b0b c"))
    }

    /** [PS4] The process dies before the state is saved again: the file tail still wins (CP133). */
    @Test
    fun crashBeforeStateSaveStillIncreases() {
        val first = log()
        first.event("a") // saved (first event)
        now += 100
        first.event("b") // < 2 s later: not saved
        first.event("c")
        assertEquals(1L, store().load().seq)
        val second = log(session = "0b0b") // "after the crash"
        second.event("d")
        val seqs = fileLines().map { EventLog.seqOf(it)!! }
        assertEquals(listOf(1L, 2L, 3L, 4L), seqs)
    }

    @Test
    fun stateSavedAtMostEvery2s() {
        val l = log()
        l.event("a")
        assertEquals(1L, store().load().seq)
        now += 1_000
        l.event("b")
        assertEquals(1L, store().load().seq)
        now += 1_500
        l.event("c")
        assertEquals(3L, store().load().seq)
        l.markShipped(2)
        assertEquals(LogState(3, 2, "7f3a"), store().load())
        l.markShipped(1) // never goes back
        assertEquals(2L, l.shipped)
    }

    @Test
    fun rotationKeepsTwoFiles() {
        val l = log(max = 5_000)
        repeat(200) { l.event("diag", "tag" to "t", "msg" to "line $it " + "x".repeat(50)) }
        val cur = File(dir, EventLog.FILE_NAME)
        val old = File(dir, EventLog.OLD_FILE_NAME)
        assertTrue(old.isFile && cur.length() < 5_000 && old.length() < 5_200)
        assertEquals(200L, EventLog.seqOf(cur.readLines().last()))
        val again = log(max = 5_000) // seq from the file tail after rotation too
        again.event("x")
        assertEquals(201L, again.lastSeq)
    }

    @Test
    fun seqFromRotatedFileWhenCurrentIsEmpty() {
        File(dir, EventLog.OLD_FILE_NAME).writeText("2025-11-24 16:00:00.000 #41 s=aaaa tap id=x\n")
        File(dir, EventLog.FILE_NAME).writeText("")
        val l = log()
        l.event("y")
        assertEquals(42L, l.lastSeq)
    }

    @Test
    fun normalDropsDetailed() {
        val l = log(detail = Detail.Normal)
        l.detailed("disc", "target" to "pc", "result" to "found", "ms" to 12)
        l.event("screen", "name" to "home")
        assertEquals(1, fileLines().size)
        assertTrue(fileLines().single().endsWith(" screen name=home"))
        val d = log(detail = Detail.Detailed)
        d.detailed("disc", "target" to "pc")
        assertTrue(fileLines().last().contains(" disc target=pc"))
    }

    /** [PS3] 2.0 androidTests search `AppGraph.ringLog.snapshot()` for message text with quotes and colons. */
    @Test
    fun diagMessageStillContainsItsOriginalText() {
        val l = log()
        val msg = "photo location permission: \"granted\" (user said yes)"
        l.event("diag", "tag" to "send", "msg" to msg)
        l.event("diag", "tag" to "engine", "msg" to "finished: 3 saved")
        val tail = l.snapshot()
        assertTrue(tail[0], tail[0].contains(msg))
        assertTrue(tail[1], tail[1].contains(" engine finished:"))
        assertFalse(fileLines()[0].contains(msg)) // the file line is escaped …
        assertTrue(fileLines()[0].contains("msg=\"photo location permission: \\\"granted\\\" (user said yes)\""))
    }

    @Test
    fun tailKeepsLast2000() {
        val l = log()
        repeat(2_100) { l.event("tap", "id" to "b$it") }
        val s = l.snapshot()
        assertEquals(2_000, s.size)
        assertTrue(s.first().endsWith(" tap id=b100"))
    }

    @Test
    fun tzOffset() {
        assertEquals("+05:30", EventLog.tzOffset(TimeZone.getTimeZone("Asia/Kolkata"), now))
        assertEquals("+00:00", EventLog.tzOffset(utc, now))
        assertEquals("-03:00", EventLog.tzOffset(TimeZone.getTimeZone("GMT-3"), now))
    }

    @Test
    fun brokenStateFileReadsAsEmpty() {
        File(dir, EventLog.STATE_FILE_NAME).writeText("{not json")
        val l = log()
        l.event("a")
        assertEquals(1L, l.lastSeq)
    }
}
