package app.ferry

import app.ferry.player.CodecName
import app.ferry.player.ResumeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan §4.13: resume is offered only > 30 s in and > 30 s before the end (design §5.2 [A12]). */
class ResumeRuleTest {
    @Test
    fun offeredOnlyBetweenTheEdges() {
        val dur = 300_000L
        assertFalse(ResumeRule.offer(0, dur))
        assertFalse(ResumeRule.offer(30_000, dur))
        assertTrue(ResumeRule.offer(30_001, dur))
        assertTrue(ResumeRule.offer(40_000, dur))
        assertTrue(ResumeRule.offer(269_999, dur))
        assertFalse(ResumeRule.offer(270_000, dur))
        assertFalse(ResumeRule.offer(299_000, dur))
    }

    @Test
    fun shortVideoIsNeverOffered() {
        assertFalse(ResumeRule.offer(31_000, 60_000)) // 31 s in, 29 s before the end
        assertFalse(ResumeRule.offer(5_000, 6_000))
    }

    @Test
    fun unknownDurationNeedsOnlyTheStartEdge() {
        assertTrue(ResumeRule.offer(40_000, -1))
        assertFalse(ResumeRule.offer(20_000, 0))
    }

    @Test
    fun entryRoundTripAndBadValues() {
        val e = ResumeRule.Entry(40_123, 300_000, 1_700_000_000_000)
        assertEquals(e, ResumeRule.decode(ResumeRule.encode(e)))
        assertNull(ResumeRule.decode(null))
        assertNull(ResumeRule.decode("12"))
        assertNull(ResumeRule.decode("a;b;c"))
        assertNull(ResumeRule.decode("-5;10;1"))
    }

    @Test
    fun keyIsNameSizeMtime() {
        assertEquals("big.mp4|241900000|1700000000000", ResumeRule.key("big.mp4", 241_900_000, 1_700_000_000_000))
        assertEquals("a.mp4|5|0", ResumeRule.key("a.mp4", 5, null))
    }

    @Test
    fun cappedAt200OldestFirst() {
        val saved = (1..200).associate { "k$it" to it.toLong() }
        assertEquals(listOf("k1"), ResumeRule.evict(saved, "new"))
        assertEquals(emptyList<String>(), ResumeRule.evict(saved, "k5")) // updating an entry adds nothing
        assertEquals(emptyList<String>(), ResumeRule.evict(mapOf("a" to 1L), "b"))
        val over = (1..203).associate { "k$it" to (1000 - it).toLong() } // newest first by key
        assertEquals(listOf("k203", "k202", "k201", "k200"), ResumeRule.evict(over, "x"))
    }

    @Test
    fun clockFormat() {
        assertEquals("0:00", ResumeRule.clock(0))
        assertEquals("0:40", ResumeRule.clock(40_900))
        assertEquals("12:34", ResumeRule.clock(754_000))
        assertEquals("1:02:03", ResumeRule.clock(3_723_000))
    }

    @Test
    fun codecNames() {
        assertEquals("DTS", CodecName.of("audio/vnd.dts", null))
        assertEquals("HEVC", CodecName.of("video/hevc", "hvc1.1.6.L93.B0"))
        assertEquals("AC3", CodecName.of("audio/ac3", null))
        assertEquals("MPEG", CodecName.of("audio/mpeg", null))
        assertEquals("HVC1", CodecName.of(null, "hvc1.1.6.L93"))
        assertEquals("unknown", CodecName.of(null, null))
    }
}
