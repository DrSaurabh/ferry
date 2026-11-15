package app.ferry.core.upload

import app.ferry.core.Clock
import app.ferry.core.model.UploadReply
import app.ferry.core.net.FerryApi
import app.ferry.core.net.FerryException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** `UploadEngine` against [FakeApi] in virtual time (plan §3.7, design §4.4). */
@OptIn(ExperimentalCoroutinesApi::class)
class UploadEngineTest {
    private val ids = AtomicLong()

    private fun item(name: String, size: Long = 100_000, original: Boolean = true, source: FakeSource = FakeSource(size, name)) =
        UploadItem(ids.incrementAndGet(), source, name, size, 1_600_000_000_000L, original)

    private class Env(val scope: TestScope, config: EngineConfig, rediscover: (suspend () -> FerryApi?)?) {
        val clock = Clock { scope.testScheduler.currentTime }
        val fake = FakeApi(clock)
        val rediscoverTimes = mutableListOf<Long>()
        val engine = UploadEngine(
            scope.backgroundScope,
            { fake },
            {
                rediscoverTimes += clock.nowMs()
                rediscover?.invoke()
            },
            clock,
            config,
            log = { println("[${clock.nowMs()}] $it") },
        )

        fun statuses(): Map<String, ItemStatus> = engine.state.value.items.associate { it.name to it.status }
    }

    private fun TestScope.env(config: EngineConfig = EngineConfig(), rediscover: (suspend () -> FerryApi?)? = null) =
        Env(this, config, rediscover)

    private fun interval(events: List<String>, name: String): LongRange {
        val s = events.first { it.startsWith("start:$name@") }.substringAfter('@').toLong()
        val e = events.last { it.startsWith("end:$name@") }.substringAfter('@').toLong()
        return s..e
    }

    @Test
    fun happyPathTwoWorkersInSelectionOrder() = runTest {
        val env = env()
        val names = (1..6).map { "IMG_$it.jpg" }
        env.engine.add(names.map { item(it, 200_000) })
        val s = env.engine.awaitFinished()
        assertEquals(names, env.fake.started.toList())
        assertEquals(2, env.fake.maxActive)
        assertEquals(6, s.saved)
        assertNull(s.stop)
        assertEquals(Phase.Finished, env.engine.state.value.phase)
        assertEquals("FAKE-PC", env.engine.state.value.pcName)
        assertTrue(env.statuses().values.all { it is ItemStatus.Saved && !it.renamed })
        assertEquals(env.engine.state.value.totalBytes, env.engine.state.value.sentBytes)
    }

    @Test
    fun duplicatesMarkedAlreadyOnPcBeforeSending() = runTest {
        val env = env()
        env.fake.onPc["b.jpg"] = 100_000
        env.engine.add(listOf(item("a.jpg"), item("b.jpg"), item("c.jpg")))
        val s = env.engine.awaitFinished()
        assertEquals(listOf("a.jpg", "c.jpg"), env.fake.started.toList())
        assertEquals(ItemStatus.AlreadyOnPc("b.jpg"), env.statuses()["b.jpg"])
        assertEquals(1, s.alreadyOnPc)
        assertEquals(2, s.saved)
    }

    @Test
    fun allDuplicateResendNeedsZeroBytes() = runTest { // [B3]
        val env = env()
        env.fake.checkFree = 0
        for (n in listOf("a.jpg", "b.jpg", "c.jpg")) env.fake.onPc[n] = 100_000
        env.engine.add(listOf(item("a.jpg"), item("b.jpg"), item("c.jpg")))
        val s = env.engine.awaitFinished()
        assertEquals(3, s.alreadyOnPc)
        assertNull(s.stop)
        assertEquals(0, env.fake.uploadCalls.get())
    }

    @Test
    fun neededMoreThanFreeStopsBeforeSending() = runTest { // [B3]
        val env = env(EngineConfig(checkChunk = 2))
        env.fake.checkFree = 250_000
        env.engine.add(listOf(item("a.jpg"), item("b.jpg"), item("c.jpg")))
        val s = env.engine.awaitFinished()
        assertEquals(StopReason.DiskFull(50_000), s.stop)
        assertEquals(2, env.fake.checkCalls.get()) // needed_bytes summed over both chunks
        assertEquals(0, env.fake.uploadCalls.get())
        assertEquals(3, s.notSent)
        assertTrue(env.statuses().values.all { it == ItemStatus.NotSent(StopReason.DiskFull(50_000)) })
    }

    @Test
    fun checkBatchHttpErrorUploadsUnchecked() = runTest { // [B4]
        val env = env()
        env.fake.checkError = { FerryException.Http(500, "boom") }
        env.fake.onPc["b.jpg"] = 100_000
        env.engine.add(listOf(item("a.jpg"), item("b.jpg")))
        val s = env.engine.awaitFinished()
        assertEquals(2, env.fake.uploadCalls.get())
        assertEquals(1, s.saved)
        assertEquals(1, s.alreadyOnPc) // the PC's hash still skipped it
        assertNull(s.stop)
    }

    @Test
    fun checkBatchTimeoutWithPcReachableUploadsUnchecked() = runTest { // [B4]
        val env = env()
        env.fake.checkError = { FerryException.Timeout("response", java.net.SocketTimeoutException()) }
        env.engine.add(listOf(item("a.jpg"), item("b.jpg")))
        val s = env.engine.awaitFinished()
        assertEquals(2, s.saved)
        assertEquals(1, env.fake.checkCalls.get())
    }

    @Test
    fun networkDropEntersWaitingAndResumes() = runTest {
        val env = env()
        env.fake.hook = { attempt, item, p ->
            if (item.name == "a.jpg" && attempt == 1) {
                delay(100)
                p(65_536)
                reachable = false
                throw FerryException.ConnectionLost(false, 65_536, IOException("drop"))
            }
            transfer(item, p)
        }
        env.engine.add(listOf(item("a.jpg", 1_000_000), item("b.jpg", 1_000_000), item("c.jpg", 1_000_000)))
        env.engine.state.first { it.phase == Phase.WaitingForPc }
        delay(10_000)
        env.fake.reachable = true
        val s = env.engine.awaitFinished()
        assertEquals(3, s.saved)
        assertEquals(3, env.fake.onPc.size)
        assertTrue(env.fake.onPc.keys.none { it.contains("(1)") })
    }

    @Test
    fun waitingForPcEndsAfter30MinWithNotSent() = runTest { // [B20]
        val env = env()
        env.fake.reachable = false
        env.engine.add(listOf(item("a.jpg"), item("b.jpg")))
        val s = env.engine.awaitFinished()
        assertEquals(StopReason.PcNotFound, s.stop)
        assertEquals(2, s.notSent)
        assertTrue(env.statuses().values.all { it == ItemStatus.NotSent(StopReason.PcNotFound) })
        val t = testScheduler.currentTime
        assertTrue("finished at $t", t in 30 * 60_000L..30 * 60_000L + 10_000)
        assertEquals(0, env.fake.uploadCalls.get())
        // probed about every 3 s
        assertTrue("info calls ${env.fake.infoCalls.get()}", env.fake.infoCalls.get() in 590..610)
    }

    @Test
    fun rediscoversEvery30sWhileWaiting() = runTest {
        lateinit var second: FakeApi
        var calls = 0
        val env = env(rediscover = {
            calls++
            if (calls == 2) second else null
        })
        second = FakeApi(env.clock)
        env.fake.reachable = false
        env.engine.add(listOf(item("a.jpg"), item("b.jpg")))
        val s = env.engine.awaitFinished()
        assertEquals(2, s.saved)
        assertEquals(2, env.rediscoverTimes.size)
        assertEquals(30_000L, env.rediscoverTimes[1] - env.rediscoverTimes[0])
        assertEquals(0, env.fake.uploadCalls.get())
        assertEquals(2, second.uploadCalls.get()) // the re-discovered PC took over
    }

    @Test
    fun threeNetFailuresWhileReachableFails() = runTest {
        val env = env()
        env.fake.hook = { _, item, p ->
            if (item.name == "x.jpg") {
                delay(50)
                p(1_000)
                throw FerryException.ConnectionLost(false, 1_000, IOException("reset"))
            }
            transfer(item, p)
        }
        env.engine.add(listOf(item("x.jpg"), item("y.jpg")))
        val s = env.engine.awaitFinished()
        val x = env.statuses()["x.jpg"]
        assertTrue("$x", x is ItemStatus.Failed && x.reason == FailReason.ConnectionKeptDropping)
        assertEquals(3, env.fake.uploadedItems.count { it.name == "x.jpg" })
        assertEquals(1, s.saved)
        assertEquals(1, s.failed)
    }

    @Test
    fun diskFull507StopsQueue() = runTest {
        val env = env()
        env.fake.hook = { _, item, p ->
            if (item.name == "b.jpg") {
                delay(10)
                throw FerryException.DiskFull()
            }
            transfer(item, p)
        }
        env.engine.add(listOf("a", "b", "c", "d").map { item("$it.jpg", 1_000_000) })
        val s = env.engine.awaitFinished()
        assertEquals(StopReason.DiskFull(null), s.stop)
        assertEquals(listOf("a.jpg", "b.jpg"), env.fake.started.toList()) // no retry loop, nothing after
        val st = env.statuses()
        assertTrue(st["a.jpg"] is ItemStatus.Saved) // already in flight: allowed to finish
        for (n in listOf("b.jpg", "c.jpg", "d.jpg")) assertEquals(ItemStatus.NotSent(StopReason.DiskFull(null)), st[n])
    }

    @Test
    fun midBodyDropWithLowFreeIsDiskFull() = runTest { // [B11]
        val env = env()
        env.fake.infoFree = 10L shl 20
        env.fake.hook = { _, _, p ->
            delay(100)
            p(1L shl 20)
            throw FerryException.ConnectionLost(false, 1L shl 20, IOException("reset"))
        }
        env.engine.add(listOf(item("big.mp4", 100L shl 20), item("next.jpg")))
        val s = env.engine.awaitFinished()
        assertEquals(StopReason.DiskFull(null), s.stop)
        assertEquals(ItemStatus.NotSent(StopReason.DiskFull(null)), env.statuses()["big.mp4"])
        assertEquals(1, env.fake.uploadedItems.count { it.name == "big.mp4" })
    }

    @Test
    fun bodySentThenSkippedCountsAsSaved() = runTest { // [B18]
        val env = env()
        env.fake.hook = { attempt, item, p ->
            when {
                item.name == "a.jpg" && attempt == 1 -> {
                    delay(50)
                    p(item.size)
                    throw FerryException.ConnectionLost(true, item.size, IOException("reset after body"))
                }
                else -> {
                    delay(50)
                    p(item.size)
                    UploadReply("skipped", item.name, item.size)
                }
            }
        }
        env.engine.add(listOf(item("a.jpg"), item("b.jpg")))
        val s = env.engine.awaitFinished()
        assertEquals(ItemStatus.Saved("a.jpg", false), env.statuses()["a.jpg"])
        assertEquals(ItemStatus.AlreadyOnPc("b.jpg"), env.statuses()["b.jpg"]) // control: never fully sent before
        assertEquals(1, s.saved)
        assertEquals(1, s.alreadyOnPc)
    }

    @Test
    fun cancelWaitsForBodySentReply() = runTest { // [B18]
        for ((replyAfter, expectSaved) in listOf(5_000L to true, 20_000L to false)) {
            val env = env(EngineConfig(workers = 1))
            val bodySent = CompletableDeferred<Unit>()
            env.fake.hook = { _, item, p ->
                delay(50)
                p(item.size)
                bodySent.complete(Unit)
                delay(replyAfter)
                store(item)
            }
            env.engine.add(listOf(item("a.jpg"), item("b.jpg")))
            bodySent.await()
            val t0 = testScheduler.currentTime
            env.engine.cancel()
            val s = env.engine.awaitFinished()
            val waited = testScheduler.currentTime - t0
            assertEquals(StopReason.Cancelled, s.stop)
            assertEquals(ItemStatus.Cancelled, env.statuses()["b.jpg"])
            if (expectSaved) {
                assertEquals(ItemStatus.Saved("a.jpg", false), env.statuses()["a.jpg"])
                assertTrue("waited $waited", waited in 5_000L..5_200L)
            } else {
                assertEquals(ItemStatus.Cancelled, env.statuses()["a.jpg"])
                assertTrue("waited $waited", waited in 10_000L..10_200L)
            }
        }
    }

    @Test
    fun cancelAbortsInFlight() = runTest {
        val env = env(EngineConfig(workers = 1))
        val started = CompletableDeferred<Unit>()
        var aborted = false
        env.fake.hook = { _, _, p ->
            p(1_000)
            started.complete(Unit)
            try {
                delay(600_000)
                throw AssertionError("not aborted")
            } catch (e: CancellationException) {
                aborted = true
                throw e
            }
        }
        env.engine.add(listOf(item("a.jpg"), item("b.jpg")))
        started.await()
        val t0 = testScheduler.currentTime
        env.engine.cancel()
        val s = env.engine.awaitFinished()
        assertTrue(aborted)
        assertTrue(testScheduler.currentTime - t0 < 1_000)
        assertEquals(2, s.cancelled)
        assertEquals(StopReason.Cancelled, s.stop)
        assertEquals(1, env.fake.uploadCalls.get())
    }

    @Test
    fun fileChangedRefreshesOnceThenFails() = runTest { // [B5]
        val env = env()
        val xs = FakeSource(100_000, "x.jpg")
        val ys = FakeSource(100_000, "y.jpg").apply { refreshMeta = SourceMeta(150_000, 42L) }
        env.fake.hook = { attempt, item, p ->
            if (item.name == "x.jpg" || attempt == 1) {
                delay(30)
                throw FerryException.SourceChanged()
            }
            transfer(item, p)
        }
        env.engine.add(listOf(item("x.jpg", source = xs), item("y.jpg", source = ys)))
        val s = env.engine.awaitFinished()
        assertEquals(ItemStatus.Failed(FailReason.FileChanged), env.statuses()["x.jpg"])
        assertEquals(1, xs.refreshes.get())
        assertEquals(2, env.fake.uploadedItems.count { it.name == "x.jpg" })
        assertTrue(env.statuses()["y.jpg"] is ItemStatus.Saved)
        val y2 = env.fake.uploadedItems.last { it.name == "y.jpg" }
        assertEquals(150_000L, y2.size)
        assertEquals(42L, y2.mtimeMs)
        assertEquals(150_000L, env.engine.state.value.items.first { it.name == "y.jpg" }.size)
        assertEquals(1, s.saved)
        assertEquals(1, s.failed)
    }

    @Test
    fun sizeMismatchAtOpenRefreshes() = runTest { // [B5]
        val env = env()
        val a = FakeSource(100_000, "a.jpg").apply {
            openLengths.addLast(120_000)
            openLengths.addLast(120_000)
            refreshMeta = SourceMeta(120_000, 7L)
        }
        val b = FakeSource(100_000, "b.jpg").apply {
            repeat(3) { openLengths.addLast(130_000) }
            refreshMeta = SourceMeta(120_000, 7L)
        }
        env.engine.add(listOf(item("a.jpg", source = a), item("b.jpg", source = b)))
        env.engine.awaitFinished()
        assertTrue(env.statuses()["a.jpg"] is ItemStatus.Saved)
        assertEquals(listOf(120_000L), env.fake.uploadedItems.map { it.size })
        assertEquals(ItemStatus.Failed(FailReason.FileChanged), env.statuses()["b.jpg"])
        assertEquals(1, b.refreshes.get())
        assertEquals(2, b.opens.get())
    }

    @Test
    fun sendAnywaySkipsPrecheck() = runTest { // [A2] [B6]
        val env = env()
        env.fake.onPc["a.jpg"] = 100_000
        val a = item("a.jpg")
        env.engine.add(listOf(a))
        assertEquals(1, env.engine.awaitFinished().alreadyOnPc)
        assertEquals(0, env.fake.uploadCalls.get())
        env.fake.hook = { _, item, p ->
            delay(20)
            p(item.size)
            UploadReply("renamed", "a (1).jpg", item.size) // the PC copy had different content (e.g. no location)
        }
        env.engine.sendAnyway(listOf(a.id))
        val s = env.engine.awaitFinished()
        assertEquals(1, env.fake.checkCalls.get()) // no second pre-check
        assertTrue(env.fake.uploadedItems.single().skipPrecheck)
        assertEquals(ItemStatus.Saved("a (1).jpg", true), env.statuses()["a.jpg"])
        assertEquals(1, s.saved)
        assertEquals(1, s.renamed)
        assertEquals(0, s.alreadyOnPc)
    }

    @Test
    fun withoutLocationCountedInSummary() = runTest { // [A2]
        val env = env()
        env.engine.add(listOf(item("a.jpg"), item("b.jpg", original = false)))
        val s = env.engine.awaitFinished()
        assertEquals(2, s.saved)
        assertEquals(1, s.savedWithoutLocation)
        assertEquals(false, env.engine.state.value.items.first { it.name == "b.jpg" }.original)
    }

    @Test
    fun oldPcStopsWithNothingSent() = runTest { // [B15]
        val env = env()
        env.fake.oldPc = true
        env.engine.add(listOf(item("a.jpg"), item("b.jpg")))
        val s = env.engine.awaitFinished()
        assertEquals(StopReason.OldPc, s.stop)
        assertEquals(2, s.notSent)
        assertEquals(0, env.fake.uploadCalls.get())
        assertEquals(0, env.fake.checkCalls.get())
    }

    @Test
    fun bigFileRunsAlone() = runTest { // [H15]
        val env = env(EngineConfig(bigFileBytes = 500_000))
        env.engine.add(listOf(item("s1.jpg"), item("s2.jpg"), item("BIG.mp4", 2_000_000), item("s3.jpg"), item("s4.jpg")))
        val s = env.engine.awaitFinished()
        assertEquals(5, s.saved)
        val ev = env.fake.events.toList()
        val big = interval(ev, "BIG.mp4")
        for (n in listOf("s1.jpg", "s2.jpg", "s3.jpg", "s4.jpg")) {
            val r = interval(ev, n)
            assertTrue("$n $r overlaps BIG $big", r.last <= big.first || r.first >= big.last)
        }
    }

    @Test
    fun bigFileKeepsItsPlaceInOrder() = runTest { // [PB14]
        val env = env(EngineConfig(bigFileBytes = 500_000))
        val smalls = (1..10).map { "s$it.jpg" }
        env.engine.add(listOf(item("s0.jpg", 300_000), item("BIG.mp4", 2_000_000)) + smalls.map { item(it, 50_000) })
        val s = env.engine.awaitFinished() // finishes: no deadlock
        assertEquals(12, s.saved)
        assertEquals(listOf("s0.jpg", "BIG.mp4") + smalls, env.fake.started.toList())
        val ev = env.fake.events.toList()
        val s0 = interval(ev, "s0.jpg")
        val big = interval(ev, "BIG.mp4")
        assertEquals("BIG starts as soon as s0 ends", s0.last, big.first)
        for (n in smalls) assertTrue("$n started inside BIG", interval(ev, n).first >= big.last)
    }

    @Test
    fun originalUnreadableFailsAndIsNotSent() = runTest { // [PB5]
        val env = env()
        val src = FakeSource(100_000, "gps.jpg").apply { openError = FerryException.OriginalUnreadable(SecurityException("no ACCESS_MEDIA_LOCATION")) }
        env.engine.add(listOf(item("gps.jpg", source = src), item("b.jpg")))
        val s = env.engine.awaitFinished()
        val st = env.statuses()["gps.jpg"]
        assertTrue("$st", st is ItemStatus.Failed && st.reason == FailReason.CantReadOriginal)
        assertEquals(0, env.fake.uploadedItems.count { it.name == "gps.jpg" })
        assertEquals(1, s.saved)
    }

    @Test
    fun addWhileRunningCombinesSummary() = runTest {
        val env = env()
        env.engine.add(listOf("a", "b", "c").map { item("$it.jpg", 1_000_000) })
        env.engine.state.first { st -> st.items.any { it.status is ItemStatus.Saved } }
        env.engine.add(listOf(item("d.jpg"), item("e.jpg")))
        val s = env.engine.awaitFinished()
        assertEquals(5, s.saved)
        assertEquals(2, env.fake.checkCalls.get())
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.jpg"), env.fake.started.toList())
    }

    @Test
    fun stopForTimeLimitMarksNotSent() = runTest { // [A6]
        val env = env()
        env.engine.add(listOf("a", "b", "c").map { item("$it.jpg", 10_000_000) })
        env.engine.state.first { st -> st.items.count { it.status is ItemStatus.Sending } == 2 }
        env.engine.stopForTimeLimit()
        val s = env.engine.awaitFinished()
        assertEquals(StopReason.TimeLimit, s.stop)
        assertEquals(3, s.notSent)
        assertTrue(env.statuses().values.all { it == ItemStatus.NotSent(StopReason.TimeLimit) })
    }

    @Test
    fun progressThrottledTo250ms() = runTest {
        val env = env()
        val seen = mutableListOf<Pair<Long, EngineState>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            env.engine.state.collect { seen += testScheduler.currentTime to it }
        }
        env.engine.add(listOf(item("v.mp4", 5_000_000))) // ~80 progress callbacks over ~5 s
        env.engine.awaitFinished()
        runCurrent()
        val middle = seen.drop(1).filter { it.second.phase != Phase.Finished }
        assertTrue("only ${middle.size} updates", middle.size >= 10)
        for (i in 1 until middle.size) {
            val gap = middle[i].first - middle[i - 1].first
            assertTrue("updates $gap ms apart", gap >= 250)
        }
        assertTrue(middle.map { it.second.sentBytes }.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(Phase.Finished, seen.last().second.phase)
    }

    @Test
    fun speedIsThreeSecondAverage() = runTest {
        val m = SpeedMeter(3_000)
        m.add(0, 0)
        var t = 0L
        var bytes = 0L
        while (t < 6_000) { t += 250; bytes += 250_000; m.add(t, bytes) } // 1 MB/s
        assertEquals(1_000_000L, m.bytesPerSecond(6_000))
        while (t < 9_000) { t += 250; bytes += 500_000; m.add(t, bytes) } // 2 MB/s
        assertEquals(2_000_000L, m.bytesPerSecond(9_000))
        assertEquals(0L, m.bytesPerSecond(12_500)) // stalled for > 3 s

        val env = env()
        env.fake.bytesPerMs = 1_000
        env.engine.add(listOf(item("v.mp4", 10_000_000)))
        advanceTimeBy(5_000)
        runCurrent()
        val st = env.engine.state.value
        assertEquals(Phase.Sending, st.phase)
        assertTrue("speed ${st.speedBps}", st.speedBps in 900_000L..1_100_000L)
        val eta = st.etaSeconds!!
        assertTrue("eta $eta", eta in 4L..7L)
        env.engine.awaitFinished()
    }
}
