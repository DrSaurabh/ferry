package app.ferry.core.download

import app.ferry.core.Clock
import app.ferry.core.model.OutFile
import app.ferry.core.net.FerryApi
import app.ferry.core.net.FerryException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import kotlin.random.Random

/** `DownloadQueue` against [FakeDownloadApi] in virtual time (plan §3.7, design §4.5). */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQueueTest {
    private class Env(scope: TestScope, rediscover: suspend () -> FerryApi? = { null }) {
        val clock = Clock { scope.testScheduler.currentTime }
        val bytes = HashMap<String, ByteArray>()
        val fake = FakeDownloadApi { url -> bytes.getValue(url) }
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val sinks = mutableListOf<RecordingSink>()
        val rediscoverTimes = mutableListOf<Long>()
        var waitedForPc = false
        val queue = DownloadQueue(
            scope.backgroundScope,
            { fake },
            {
                rediscoverTimes += clock.nowMs()
                rediscover()
            },
            clock,
            log = { println("[${clock.nowMs()}] $it") },
        )

        init {
            scope.backgroundScope.launch { queue.waitingForPc.collect { if (it) waitedForPc = true } }
        }

        fun file(id: Int, name: String, size: Int = 300_000): OutFile {
            val url = "/api/v1/outbox/$id/$name"
            bytes[url] = Random(id).nextBytes(size)
            return OutFile(id, name, size.toLong(), url = url)
        }

        val factory: (OutFile) -> DownloadSink = { f ->
            val n = sinks.count { it.label.startsWith(f.name + "#") } + 1
            RecordingSink("${f.name}#$n", events).also { sinks += it }
        }

        fun sinksOf(name: String) = sinks.filter { it.label.startsWith("$name#") }

        suspend fun await(id: Int, cond: (DownloadStatus?) -> Boolean): DownloadStatus? =
            queue.state.first { cond(it[id]) }[id]

        suspend fun awaitEnd(id: Int): DownloadStatus? = await(id) {
            it is DownloadStatus.Done || it is DownloadStatus.Failed || it is DownloadStatus.Cancelled
        }

        /** [PB17] for every sink: close before commit/abort, never both, never commit on an open stream. */
        fun assertContract() {
            for (s in sinks) {
                assertFalse("${s.label} commit while open", s.commitWhileOpen)
                assertFalse("${s.label} abort while open", s.abortWhileOpen)
                assertFalse("${s.label} committed and aborted", s.committed && s.aborted)
            }
        }
    }

    @Test
    fun oneAtATimeInOrder() = runTest {
        val env = Env(this)
        val files = (1..3).map { env.file(it, "f$it.jpg") }
        for (f in files) env.queue.enqueue(f, env.factory)
        for (f in files) {
            val st = env.awaitEnd(f.id)
            assertEquals(DownloadStatus.Done("saved-${f.name}#1", "uri-${f.name}#1"), st)
            assertArrayEquals(env.bytes.getValue(f.url), env.sinksOf(f.name).single().data.toByteArray())
        }
        assertEquals(1, env.fake.maxActive)
        assertEquals(files.map { it.url }, env.fake.started)
        assertEquals(listOf("f1.jpg#1:open", "f1.jpg#1:close", "f1.jpg#1:commit"), env.events.filter { it.startsWith("f1.jpg") })
        env.assertContract()
    }

    @Test
    fun enqueueTwiceIsOneDownload() = runTest {
        val env = Env(this)
        val f = env.file(1, "a.jpg")
        env.queue.enqueue(f, env.factory)
        env.queue.enqueue(f, env.factory)
        env.awaitEnd(1)
        assertEquals(1, env.fake.calls.get())
    }

    @Test
    fun shortBodyIsAbortedNotCommitted() = runTest { // [B7]
        val env = Env(this)
        val f = env.file(1, "short.mp4")
        env.fake.plans += FakeDownloadApi.Plan(deliver = f.size - 10)
        env.queue.enqueue(f, env.factory)
        assertEquals(DownloadStatus.Failed(DownloadFail.CHANGED_ON_PC), env.awaitEnd(1))
        val s = env.sinksOf("short.mp4").single()
        assertTrue(s.aborted)
        assertFalse(s.committed)
        assertEquals(listOf("short.mp4#1:open", "short.mp4#1:close", "short.mp4#1:abort"), env.events)
        env.assertContract()
    }

    @Test
    fun wrongContentLengthIsAbortedNotCommitted() = runTest { // [B7]
        val env = Env(this)
        val f = env.file(1, "x.mp4")
        env.fake.plans += FakeDownloadApi.Plan(contentLength = f.size + 1)
        env.queue.enqueue(f, env.factory)
        assertEquals(DownloadStatus.Failed(DownloadFail.CHANGED_ON_PC), env.awaitEnd(1))
        val s = env.sinksOf("x.mp4").single()
        assertTrue(s.aborted)
        assertFalse(s.committed)
        assertFalse(s.opened)
    }

    @Test
    fun dropWhilePcGoneRestartsFromZeroInFreshSink() = runTest { // [H20]
        val env = Env(this)
        val f = env.file(1, "big.mp4", 1_000_000)
        env.fake.plans += FakeDownloadApi.Plan(dropAt = 256 * 1024, reachableAfterDrop = false)
        env.queue.enqueue(f, env.factory)
        env.queue.waitingForPc.first { it }
        testScheduler.advanceTimeBy(10_000)
        assertEquals(DownloadStatus.Queued, env.queue.state.value[1])
        env.fake.reachable = true
        assertEquals(DownloadStatus.Done("saved-big.mp4#2", "uri-big.mp4#2"), env.awaitEnd(1))
        val (first, second) = env.sinksOf("big.mp4")
        assertTrue(first.aborted && !first.committed)
        assertTrue(first.data.size() in 1 until f.size)
        assertTrue(second.committed)
        assertArrayEquals(env.bytes.getValue(f.url), second.data.toByteArray()) // from zero, not appended
        assertTrue(env.waitedForPc)
        assertFalse(env.queue.waitingForPc.value)
        env.assertContract()
    }

    @Test
    fun dropWhilePcAnswersRetriesAtOnceThenGivesUp() = runTest {
        val env = Env(this)
        val f = env.file(1, "a.mp4")
        repeat(3) { env.fake.plans += FakeDownloadApi.Plan(dropAt = 64 * 1024) }
        env.queue.enqueue(f, env.factory)
        assertEquals(DownloadStatus.Failed(DownloadFail.KEPT_DROPPING), env.awaitEnd(1))
        assertEquals(3, env.fake.calls.get())
        assertTrue(env.sinksOf("a.mp4").all { it.aborted && !it.committed })
        assertFalse(env.waitedForPc)
        env.assertContract()
    }

    @Test
    fun oneDropWhilePcAnswersThenSaved() = runTest {
        val env = Env(this)
        val f = env.file(1, "a.mp4")
        env.fake.plans += FakeDownloadApi.Plan(dropAt = 64 * 1024)
        env.queue.enqueue(f, env.factory)
        assertEquals(DownloadStatus.Done("saved-a.mp4#2", "uri-a.mp4#2"), env.awaitEnd(1))
    }

    @Test
    fun pcGoneFor30MinFails() = runTest {
        val env = Env(this)
        val f = env.file(1, "a.mp4")
        env.fake.plans += FakeDownloadApi.Plan(dropAt = 64 * 1024, reachableAfterDrop = false)
        env.queue.enqueue(f, env.factory)
        val t0 = testScheduler.currentTime
        assertEquals(DownloadStatus.Failed(DownloadFail.PC_NOT_FOUND), env.awaitEnd(1))
        val waited = testScheduler.currentTime - t0
        assertTrue("waited $waited ms", waited in 30 * 60_000L..30 * 60_000L + 10_000)
        assertTrue("rediscovered ${env.rediscoverTimes.size}×", env.rediscoverTimes.size in 55..61)
        assertEquals(1, env.sinksOf("a.mp4").size)
    }

    @Test
    fun rediscoveredClientIsUsed() = runTest {
        lateinit var moved: FakeDownloadApi
        val env = Env(this) { moved } // re-discovery finds the PC at a new address
        val f = env.file(1, "a.mp4")
        moved = FakeDownloadApi { env.bytes.getValue(it) }
        env.fake.plans += FakeDownloadApi.Plan(dropAt = 64 * 1024, reachableAfterDrop = false)
        env.queue.enqueue(f, env.factory)
        assertEquals(DownloadStatus.Done("saved-a.mp4#2", "uri-a.mp4#2"), env.awaitEnd(1))
        assertEquals(1, moved.calls.get())
        assertEquals(1, env.fake.calls.get())
        assertArrayEquals(env.bytes.getValue(f.url), env.sinksOf("a.mp4")[1].data.toByteArray())
    }

    @Test
    fun cancelRunningAbortsAfterClose() = runTest {
        val env = Env(this)
        val a = env.file(1, "a.mp4")
        val b = env.file(2, "b.mp4")
        val hold = FakeDownloadApi.Plan(holdAt = 128 * 1024)
        env.fake.plans += hold
        env.queue.enqueue(a, env.factory)
        env.queue.enqueue(b, env.factory)
        env.await(1) { it is DownloadStatus.Running && it.got > 0 } // body started (progress is throttled to 250 ms)
        env.queue.cancel(1)
        assertEquals(DownloadStatus.Cancelled, env.awaitEnd(1))
        val s = env.sinksOf("a.mp4").single()
        assertTrue(s.aborted && !s.committed)
        assertEquals(listOf("a.mp4#1:open", "a.mp4#1:close", "a.mp4#1:abort"), env.events.filter { it.startsWith("a.mp4") })
        assertEquals(DownloadStatus.Done("saved-b.mp4#1", "uri-b.mp4#1"), env.awaitEnd(2))
        env.assertContract()
    }

    @Test
    fun cancelQueuedAndCancelAll() = runTest {
        val env = Env(this)
        val files = (1..4).map { env.file(it, "f$it.jpg") }
        val hold = FakeDownloadApi.Plan(holdAt = 64 * 1024)
        env.fake.plans += hold
        for (f in files) env.queue.enqueue(f, env.factory)
        env.await(1) { it is DownloadStatus.Running && it.got > 0 } // the fake has taken the "hold" plan
        env.queue.cancel(2)
        assertEquals(DownloadStatus.Cancelled, env.queue.state.value[2])
        env.queue.cancelAll()
        for (id in listOf(1, 3, 4)) assertEquals(DownloadStatus.Cancelled, env.awaitEnd(id))
        assertEquals(1, env.fake.calls.get())
        assertTrue(env.sinks.none { it.committed })
        env.assertContract()
        // the queue still works afterwards
        val f5 = env.file(5, "f5.jpg")
        env.queue.enqueue(f5, env.factory)
        assertEquals(DownloadStatus.Done("saved-f5.jpg#1", "uri-f5.jpg#1"), env.awaitEnd(5))
    }

    @Test
    fun notEnoughSpaceFailsAndNextRuns() = runTest {
        val env = Env(this)
        val a = env.file(1, "a.mp4")
        val b = env.file(2, "b.mp4")
        env.queue.enqueue(a) { throw FerryException.NotEnoughSpace(it.size, 10) }
        env.queue.enqueue(b, env.factory)
        assertEquals(DownloadStatus.Failed(DownloadFail.NOT_ENOUGH_SPACE), env.awaitEnd(1))
        assertEquals(DownloadStatus.Done("saved-b.mp4#1", "uri-b.mp4#1"), env.awaitEnd(2))
    }

    @Test
    fun goneFromPcIs404() = runTest {
        val env = Env(this)
        env.fake.plans += FakeDownloadApi.Plan(httpCode = 404)
        env.queue.enqueue(env.file(1, "a.mp4"), env.factory)
        assertEquals(DownloadStatus.Failed(DownloadFail.GONE_FROM_PC), env.awaitEnd(1))
        assertTrue(env.sinks.single().aborted)
    }

    @Test
    fun progressIsReported() = runTest {
        val env = Env(this)
        val f = env.file(1, "big.mp4", 5_000_000)
        val seen = Collections.synchronizedList(mutableListOf<DownloadStatus?>())
        backgroundScope.launch { env.queue.state.collect { seen += it[1] } }
        env.queue.enqueue(f, env.factory)
        env.awaitEnd(1)
        val running = seen.filterIsInstance<DownloadStatus.Running>()
        assertTrue("progress updates: ${running.size}", running.size >= 3)
        assertEquals(running.map { it.got }.sorted(), running.map { it.got })
        assertTrue(running.all { it.total == f.size })
    }

    /** A sink whose stream fails after [failAfter] bytes with [message] (phone storage trouble, Ph7 B58). */
    private class FailingSink(private val failAfter: Int, private val message: String) : DownloadSink {
        var aborted = false
        var committed = false
        var written = 0

        override fun open(): java.io.OutputStream = object : java.io.OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (written + len > failAfter) throw java.io.IOException(message)
                written += len
            }
        }

        override fun commit(): String {
            committed = true
            return "never"
        }

        override fun abort() {
            aborted = true
        }
    }

    @Test
    fun storageWriteErrorFailsAtOnceAsCantSave() = runTest { // B58: not a dropped connection, not retried
        val env = Env(this)
        val f = env.file(1, "a.mp4", 1_000_000)
        val sinks = mutableListOf<FailingSink>()
        env.queue.enqueue(f) { FailingSink(200_000, "write failed: EIO (I/O error)").also { sinks += it } }
        assertEquals(DownloadStatus.Failed(DownloadFail.CANT_SAVE), env.awaitEnd(1))
        assertEquals(1, env.fake.calls.get())
        assertEquals(1, sinks.size)
        assertTrue(sinks[0].aborted)
        assertFalse(sinks[0].committed)
    }

    @Test
    fun storageFullMidDownloadIsNotEnoughSpace() = runTest { // B58
        val env = Env(this)
        val f = env.file(1, "a.mp4", 1_000_000)
        val sinks = mutableListOf<FailingSink>()
        env.queue.enqueue(f) { FailingSink(300_000, "write failed: ENOSPC (No space left on device)").also { sinks += it } }
        assertEquals(DownloadStatus.Failed(DownloadFail.NOT_ENOUGH_SPACE), env.awaitEnd(1))
        assertEquals(1, env.fake.calls.get())
        assertTrue(sinks.single().aborted)
    }

    @Test
    fun noSpaceIsFoundInTheCauseChain() {
        assertTrue(SinkWriter.isNoSpace(java.io.IOException("x", java.io.IOException("No space left on device"))))
        assertFalse(SinkWriter.isNoSpace(java.io.IOException("Broken pipe")))
    }
}
