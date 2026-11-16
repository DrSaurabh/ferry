package app.ferry

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import app.ferry.ui.nav.Screen
import app.ferry.ui.tv.RowFocus
import app.ferry.ui.tv.TvViewerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TV suite class 4 (plan §4.14) [T3] [A20]: with the slideshow set to 3 s, OK on "▶ Slideshow" starts it; it advances
 * on its own about every 3 s; Right/Left change the photo; `broken.jpg` shows "Can't show this photo" and the
 * slideshow moves on; OK pauses (no advance for > 3 s) and resumes. Back → focus on the Slideshow card again; OK on
 * a photo card opens the viewer at that photo, paused (it does not advance).
 */
@RunWith(AndroidJUnit4::class)
class TvSlideshowTest {
    @Test
    fun slideshowAdvancesSkipsBrokenAndViewerStaysPut() {
        LaunchHelpers.cleanPrefs(welcomeDone = false)
        AppGraph.prefs.slideshowSeconds = 3
        TvHelpers.leaveTouchMode()
        LaunchHelpers.launch().use { sc ->
            SendHelpers.waitConnected()
            val t = TvHelpers(sc)
            val names = listOf("photo1.jpg", "photo2.jpg", "photo3.jpg", "photo4.jpg", "photo5.jpg", "broken.jpg")
            t.waitOutbox(*names.toTypedArray())
            t.waitFocus("a card focused", 20_000) { t.current != null }
            t.press("down") { it?.row == "photos" }
            t.focusFirstInRow()
            assertEquals(RowFocus("photos", 0), t.current)
            val slideshowCard = t.title!!
            assertTrue("first Photos card is the Slideshow card: $slideshowCard", slideshowCard.contains("6"))

            t.ok("slideshow shown") { it is Screen.TvViewer }
            val s = t.screen() as Screen.TvViewer
            assertTrue(s.slideshow)
            assertEquals(6, s.photos.size)
            SendHelpers.waitUntil("viewer open", 5_000) { TvViewerState.open && TvViewerState.playing }

            // Auto-advance about every 3 s: each automatic move comes ~3 s after the move to the photo it leaves.
            SendHelpers.waitUntil("two automatic advances", 30_000) { TvViewerState.moves.count { it.how == "auto" } >= 2 }
            val moves = TvViewerState.moves.toList()
            val gaps = moves.indices.filter { it > 0 && moves[it].how == "auto" }.map { moves[it].atMs - moves[it - 1].atMs }
            Probe.record("slideshow_moves", moves)
            Probe.record("slideshow_auto_gaps_ms", gaps)
            assertTrue("auto-advance gaps $gaps ms should be about 3 s", gaps.size >= 2 && gaps.all { it in 2_500..4_500 })

            // broken.jpg: the message, then the slideshow moves on.
            // broken.jpg may come first (row order) and be past by now: wait for its message on screen (the slideshow
            // loops, so it comes round again within one pass of 6 × 3 s).
            SendHelpers.waitUntil("broken.jpg message shown", 40_000) {
                TvViewerState.messageVisible && TvViewerState.name == "broken.jpg"
            }
            SendHelpers.waitText(Probe.context.getString(R.string.cant_show_photo), 1_800)
            SendHelpers.settle()
            Shots.take("tv-slideshow-broken")
            SendHelpers.waitUntil("moved on past broken.jpg", 6_000) { TvViewerState.name != "broken.jpg" }
            assertTrue(TvViewerState.moves.any { it.how == "skip" && it.index == (s.photos.indexOfFirst { p -> p.name == "broken.jpg" } + 1) % 6 })
            assertFalse("broken.jpg never decoded", "broken.jpg" in TvViewerState.shown)

            // Right / Left.
            val i0 = TvViewerState.index
            SendHelpers.device.pressDPadRight()
            SendHelpers.waitUntil("Right → next photo", 2_000) { TvViewerState.index == (i0 + 1) % 6 }
            SendHelpers.waitUntil("overlay shown", 2_000) { TvViewerState.overlayVisible }
            Thread.sleep(500)
            Shots.take("tv-slideshow-overlay")
            val i1 = TvViewerState.index
            SendHelpers.device.pressDPadLeft()
            SendHelpers.waitUntil("Left → previous photo", 2_000) { TvViewerState.index == (i1 + 5) % 6 }
            Probe.record("slideshow_keys", "$i0 → right → $i1 → left → ${TvViewerState.index}")

            // OK pauses (no move for 5 s > 3 s), OK resumes.
            SendHelpers.device.pressDPadCenter()
            SendHelpers.waitUntil("paused", 2_000) { !TvViewerState.playing }
            val pausedAt = TvViewerState.index
            val movesAtPause = TvViewerState.moves.size
            Thread.sleep(5_000)
            assertEquals("no advance while paused", movesAtPause, TvViewerState.moves.size)
            assertEquals(pausedAt, TvViewerState.index)
            SendHelpers.device.pressDPadCenter()
            SendHelpers.waitUntil("playing again", 2_000) { TvViewerState.playing }
            SendHelpers.waitUntil("advances again after resume", 8_000) { TvViewerState.moves.size > movesAtPause }

            // Back → focus on the Slideshow card; OK on a photo → viewer at that photo, paused.
            SendHelpers.device.pressBack()
            SendHelpers.waitUntil("home again", 10_000) { t.screen() is Screen.TvHome }
            t.waitFocus("focus back on the Slideshow card") { t.current == RowFocus("photos", 0) }
            t.press("right") { it == RowFocus("photos", 1) }
            if (t.title == "broken.jpg") t.press("right") { it == RowFocus("photos", 2) }
            val photo = t.title!!
            t.ok("viewer shown") { it is Screen.TvViewer }
            val v = t.screen() as Screen.TvViewer
            assertFalse("a photo card opens the viewer, not the slideshow", v.slideshow)
            assertEquals(photo, v.photos[v.start].name)
            SendHelpers.waitUntil("viewer shows $photo", 10_000) { photo in TvViewerState.shown }
            Thread.sleep(4_000)
            assertEquals("the viewer does not advance by itself", photo, TvViewerState.name)
            SendHelpers.settle()
            Shots.take("tv-viewer")
            SendHelpers.device.pressDPadRight()
            SendHelpers.waitUntil("Right in the viewer → next", 2_000) { TvViewerState.name != photo }
            SendHelpers.device.pressBack()
            SendHelpers.waitUntil("home again", 10_000) { t.screen() is Screen.TvHome }
        }
    }
}
