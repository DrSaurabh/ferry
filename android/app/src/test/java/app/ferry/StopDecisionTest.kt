package app.ferry

import app.ferry.service.StopDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * [PB10] (plan §4.4, §4.13): a send that races the idle check is never lost: either the service stays (the work was
 * queued first) or the newer start id wins (`stopSelfResult` returns false). Android is modelled by [FakeSystem]:
 * it hands out start ids when `startService` is called, and `stopSelfResult(id)` succeeds only for the newest id;
 * a start after a successful stop creates a new service instance.
 */
class StopDecisionTest {
    private class Instance {
        val decision = StopDecision()
        val queue = ArrayList<Int>()
        @Volatile var stopped = false
    }

    private class FakeSystem {
        var current = Instance()
        var lastId = 0
        val lost = AtomicInteger()
        val dropped = AtomicInteger()

        /** startService(): the id is assigned at once; onStartCommand runs later on the "main thread". */
        @Synchronized fun startService(): Pair<Instance, Int> {
            if (current.stopped) current = Instance()
            lastId++
            return current to lastId
        }

        @Synchronized fun stopSelfResult(inst: Instance, id: Int): Boolean {
            if (id != lastId) return false
            if (synchronized(inst.queue) { inst.queue.isNotEmpty() }) dropped.incrementAndGet()
            inst.stopped = true
            return true
        }

        fun onStartCommand(inst: Instance, id: Int) {
            inst.decision.started(id) {
                if (inst.stopped) lost.incrementAndGet()
                synchronized(inst.queue) { inst.queue += id }
            }
        }

        /** The service's idle check: idle = queue empty (work is "done" instantly by the drain below). */
        fun idleCheck(inst: Instance): Boolean {
            val id = inst.decision.stopId { synchronized(inst.queue) { inst.queue.isEmpty() } } ?: return false
            return stopSelfResult(inst, id)
        }
    }

    @Test
    fun checkBeforeTheNewStartIsDeliveredLosesToTheNewerId() {
        val sys = FakeSystem()
        val (inst, id1) = sys.startService()
        sys.onStartCommand(inst, id1)
        synchronized(inst.queue) { inst.queue.clear() } // first batch done
        val stop = inst.decision.stopId { true }
        assertEquals(id1, stop)
        val (inst2, id2) = sys.startService() // a share arrives now
        assertTrue(inst2 === inst)
        assertFalse("the newer start id wins", sys.stopSelfResult(inst, stop!!))
        sys.onStartCommand(inst, id2)
        assertEquals(listOf(id2), inst.queue)
        assertFalse(inst.stopped)
    }

    @Test
    fun workQueuedBeforeTheCheckKeepsTheServiceAlive() {
        val sys = FakeSystem()
        val (inst, id1) = sys.startService()
        sys.onStartCommand(inst, id1)
        assertNull(inst.decision.stopId { inst.queue.isEmpty() })
    }

    @Test
    fun startAfterASuccessfulStopGoesToANewInstance() {
        val sys = FakeSystem()
        val (inst, id1) = sys.startService()
        sys.onStartCommand(inst, id1)
        inst.queue.clear()
        assertTrue(sys.idleCheck(inst))
        val (inst2, id2) = sys.startService()
        assertFalse(inst2 === inst)
        sys.onStartCommand(inst2, id2)
        assertEquals(listOf(id2), inst2.queue)
        assertEquals(0, sys.lost.get())
    }

    @Test
    fun randomInterleavingsNeverLoseWork() {
        repeat(300) {
            val sys = FakeSystem()
            val (first, id0) = sys.startService()
            sys.onStartCommand(first, id0)
            synchronized(first.queue) { first.queue.clear() }
            val go = CountDownLatch(1)
            val sender = thread {
                go.await()
                repeat(3) {
                    val (inst, id) = sys.startService()
                    sys.onStartCommand(inst, id)
                }
            }
            val checker = thread {
                go.await()
                repeat(5) {
                    val inst = synchronized(sys) { sys.current }
                    // drain what was queued (the engine finished it), then check idle
                    synchronized(inst.queue) { inst.queue.clear() }
                    sys.idleCheck(inst)
                }
            }
            go.countDown()
            sender.join()
            checker.join()
            assertEquals("work delivered to a stopped instance", 0, sys.lost.get())
            assertEquals("stopped with work still queued", 0, sys.dropped.get())
        }
    }
}
