package app.ferry

import app.ferry.net.RetrySchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M19 (P14): phone discovery back-off — 10 s for the first minute, then 60 s; reset starts over. */
class RetryScheduleTest {
    /** Runs the schedule second by second from [from] to [to] and returns the times retries fired. */
    private fun fires(s: RetrySchedule, from: Long, to: Long): List<Long> {
        val out = ArrayList<Long>()
        var t = from
        while (t <= to) {
            if (s.due(t)) { s.fired(t); out.add(t) }
            t += 1_000
        }
        return out
    }

    @Test fun tenSecondsForAMinuteThenSixty() {
        val s = RetrySchedule(0)
        val f = fires(s, 0, 300_000)
        assertEquals(listOf(10_000L, 20_000L, 30_000L, 40_000L, 50_000L, 60_000L, 120_000L, 180_000L, 240_000L, 300_000L), f)
        assertEquals(10, s.retries)
    }

    @Test fun resetGoesBackToTheFastMinute() {
        val s = RetrySchedule(0)
        fires(s, 0, 200_000) // in the slow part now (next at 240 s)
        assertFalse(s.due(230_000))
        s.reset(230_000)
        assertEquals(0, s.retries)
        assertFalse(s.due(239_000))
        assertTrue(s.due(240_000))
        assertEquals(1, s.fired(240_000))
        assertEquals(10_000L, s.interval(245_000))
    }

    @Test fun tvKeepsTenSeconds() {
        val s = RetrySchedule(0, fixed = true)
        val f = fires(s, 0, 200_000)
        assertEquals(20, f.size)
        assertEquals(10_000L, s.interval(1_000_000))
    }

    @Test fun nothingDueBeforeTheFirstInterval() {
        val s = RetrySchedule(5_000)
        assertFalse(s.due(14_999))
        assertTrue(s.due(15_000))
    }
}
