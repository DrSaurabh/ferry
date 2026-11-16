package app.ferry

import app.ferry.cast.CastStateMapper
import app.ferry.cast.CastWatch
import app.ferry.cast.Reachability
import app.ferry.core.model.CastItem
import app.ferry.core.model.CastRequest
import app.ferry.core.model.CastState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan §4.13: the TV's cast state, the Remote's lines and the give-up rules of both sides (design §5.5, §6). */
class CastStateMapperTest {
    @Test
    fun videoStateFromThePlayer() {
        val s = CastStateMapper.video(0, 1, "a.mp4", playWhenReady = true, ended = false, failed = false, positionMs = 12_345, durationMs = 60_000)
        assertEquals(CastRequest.VIDEO, s.kind)
        assertTrue(s.playing)
        assertEquals(12_345, s.positionMs)
        assertEquals(60_000, s.durationMs)
        assertEquals(1, s.count)
        assertEquals("a.mp4", s.title)
    }

    @Test
    fun videoUnknownDurationNegativePositionEndAndError() {
        val unknown = CastStateMapper.video(0, 1, null, true, false, false, -5, Long.MIN_VALUE + 1) // ExoPlayer TIME_UNSET
        assertEquals(0, unknown.durationMs)
        assertEquals(0, unknown.positionMs)
        assertTrue("buffering at the start counts as playing", unknown.playing)
        val past = CastStateMapper.video(0, 1, null, true, false, false, 70_000, 60_000)
        assertEquals(60_000, past.positionMs)
        assertFalse(CastStateMapper.video(0, 1, null, true, ended = true, failed = false, positionMs = 0, durationMs = 1).playing)
        assertFalse(CastStateMapper.video(0, 1, null, true, ended = false, failed = true, positionMs = 0, durationMs = 1).playing)
        assertFalse(CastStateMapper.video(0, 1, null, false, false, false, 0, 1).playing)
    }

    @Test
    fun photosAndStarting() {
        val p = CastStateMapper.photos(2, 24, "c.jpg", playing = false, intervalMs = 3_000)
        assertEquals(CastRequest.PHOTOS, p.kind)
        assertEquals(2, p.index)
        assertFalse(p.playing)
        assertEquals(3_000, p.intervalMs)

        val items = listOf(CastItem("http://p/0", "a.jpg", "image/jpeg"), CastItem("http://p/1", "b.jpg", "image/jpeg"))
        val st = CastStateMapper.starting(CastRequest(CastRequest.PHOTOS, "phone", 10_000, 1, items))
        assertEquals(CastRequest.PHOTOS, st.kind)
        assertEquals(1, st.index)
        assertEquals("b.jpg", st.title)
        assertEquals(2, st.count)
        assertTrue(st.playing)
        assertEquals(10_000, st.intervalMs)
        val v = CastStateMapper.starting(CastRequest(CastRequest.VIDEO, "phone", 5_000, 0, items.take(1)))
        assertEquals(0, v.intervalMs)
        assertEquals("a.jpg", v.title)
    }

    @Test
    fun remoteProgressLines() {
        assertEquals("1:23 / 5:00", CastStateMapper.progress(CastState(kind = "video", positionMs = 83_400, durationMs = 300_000)))
        assertEquals("0:07", CastStateMapper.progress(CastState(kind = "video", positionMs = 7_000, durationMs = 0)))
        assertEquals("1:02:03 / 2:00:00", CastStateMapper.progress(CastState(kind = "video", positionMs = 3_723_000, durationMs = 7_200_000)))
        assertEquals("3 / 24", CastStateMapper.progress(CastState(kind = "photos", index = 2, count = 24)))
        assertEquals("", CastStateMapper.progress(CastState()))
    }

    @Test
    fun intervalChoiceIsTheNearestOfThree() {
        assertEquals(3, CastStateMapper.intervalChoice(3_000))
        assertEquals(5, CastStateMapper.intervalChoice(5_000))
        assertEquals(10, CastStateMapper.intervalChoice(10_000))
        assertEquals(10, CastStateMapper.intervalChoice(60_000))
        assertEquals(3, CastStateMapper.intervalChoice(1_000))
        assertEquals(5, CastStateMapper.intervalChoice(0))
    }

    @Test
    fun reachabilityGivesUpAfterTheLimitWithoutAnAnswer() {
        val r = Reachability(startMs = 1_000, limitMs = 10_000)
        assertFalse(r.failed(5_000))
        assertFalse(r.failed(10_999))
        assertTrue("nothing answered for 10 s since the start", r.failed(11_000))
        r.ok(12_000)
        assertFalse("an answer resets the clock", r.failed(21_999))
        assertTrue(r.failed(22_000))
    }

    @Test
    fun castEndsAfterTwoIdleAnswersInARow() {
        val w = CastWatch()
        val playing = CastState(kind = "video", playing = true)
        assertFalse(w.onState(playing))
        assertFalse(w.onState(CastState()))
        assertFalse("a cast in between resets the count", w.onState(playing))
        assertFalse(w.onState(CastState()))
        assertTrue(w.onState(CastState()))
    }
}
