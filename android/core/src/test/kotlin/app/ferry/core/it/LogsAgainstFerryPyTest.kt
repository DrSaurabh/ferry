package app.ferry.core.it

import app.ferry.core.Clock
import app.ferry.core.log.EventLog
import app.ferry.core.log.LogReader
import app.ferry.core.log.LogStateStore
import app.ferry.core.net.FerryClient
import app.ferry.core.net.FerryHttp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 2.1 `POST /api/v1/logs` against the real ferry.py (plan §1.2): append, repeat dropped, device folder, headers. */
class LogsAgainstFerryPyTest {
    private val pcs = mutableListOf<FerryPcProcess>()
    private val dir: File = Files.createTempDirectory("ferry-logs-it").toFile()

    @After
    fun tearDown() {
        pcs.forEach { it.close() }
        dir.deleteRecursively()
    }

    @Test
    fun shipAppendRepeatAndFolder(): Unit = runBlocking {
        val pc = FerryPcProcess.start().also { pcs += it }
        val client = FerryClient(FerryHttp.newClient(null, "vivo V2413", "2.1.0-dev", "a3f9", "phone"), pc.base)
        val info = client.info()
        assertTrue(info.capabilities.contains("logs"))
        assertTrue("time_ms ${info.timeMs}", Math.abs(info.timeMs!! - System.currentTimeMillis()) < 60_000)

        val log = EventLog(dir, Clock { System.currentTimeMillis() }, LogStateStore(File(dir, EventLog.STATE_FILE_NAME)))
        log.event("app.start", "ver" to "2.1.0-dev", "api" to 34, "tz" to "+05:30")
        log.event("tap", "id" to "send", "screen" to "picker")
        val reader = LogReader(dir)
        val first = reader.linesAfter(log.shipped)
        val r1 = client.postLogs(first.fromSeq, first.body)
        assertEquals(2, r1.stored)
        assertEquals(2L, r1.lastSeq)
        assertTrue(r1.timeMs != null)

        // The reply was "lost": the same lines plus a new one come again; only the new one is stored.
        log.event("err.shown", "text" to "PC not found — is Ferry running on the PC?")
        val again = reader.linesAfter(0)
        val r2 = client.postLogs(again.fromSeq, again.body)
        assertEquals(1, r2.stored)
        assertEquals(3L, r2.lastSeq)
        log.markShipped(r2.lastSeq)
        assertTrue(reader.linesAfter(log.shipped).isEmpty)

        val folder = File(pc.data, "logs/vivo V2413 (a3f9)")
        val lines = File(folder, "app-log.txt").readLines(Charsets.UTF_8)
        assertEquals(listOf(1L, 2L, 3L), lines.map { EventLog.seqOf(it) })
        assertTrue(lines[2], lines[2].endsWith("err.shown text=\"PC not found — is Ferry running on the PC?\""))
        assertEquals("3", File(folder, ".last-seq").readText().trim())
        assertTrue(pc.awaitLogUpTo().any { it.contains("/api/v1/logs") && it.contains("log 1 line") })
    }
}
