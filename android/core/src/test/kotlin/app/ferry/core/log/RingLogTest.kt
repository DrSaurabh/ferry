package app.ferry.core.log

import app.ferry.core.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** `RingLog` (plan §3.7, design §9). */
class RingLogTest {
    private val clock = Clock { 1_700_000_000_000L }

    @Test
    fun keepsLast2000Lines() {
        val log = RingLog(null, clock)
        for (i in 1..2_500) log.log("t", "line $i")
        val s = log.snapshot()
        assertEquals(2_000, s.size)
        assertTrue(s.first(), s.first().endsWith(" t line 501"))
        assertTrue(s.last(), s.last().endsWith(" t line 2500"))
    }

    @Test
    fun oneLinePerEntry() {
        val log = RingLog(null, clock)
        log.log("net", "a\nb\r\nc")
        assertEquals(1, log.snapshot().size)
        assertTrue(log.snapshot()[0], log.snapshot()[0].endsWith(" net a b  c"))
    }

    @Test
    fun rotatesFileAt1MB() {
        val dir = Files.createTempDirectory("ringlog").toFile()
        try {
            val f = File(dir, "ferry-app-log.txt")
            val log = RingLog(f, clock)
            val pad = "x".repeat(200)
            var i = 0
            while (!log.previousFile!!.exists()) {
                log.log("t", "${i++} $pad")
                assertTrue("never rotated", i < 10_000)
            }
            val old = log.previousFile!!
            assertTrue("rotated at ${old.length()}", old.length() >= (1L shl 20) && old.length() < (1L shl 20) + 1_000)
            log.log("t", "after rotation")
            assertTrue(f.readText().endsWith("t after rotation\n"))
            assertEquals(1, f.readLines().size)
            assertEquals(2_000, log.snapshot().size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun appendsToExistingFile() {
        val dir = Files.createTempDirectory("ringlog").toFile()
        try {
            val f = File(dir, "sub/ferry-app-log.txt")
            RingLog(f, clock).log("a", "first")
            RingLog(f, clock).log("b", "second")
            val lines = f.readLines()
            assertEquals(2, lines.size)
            assertTrue(lines[1].endsWith(" b second"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
