package app.ferry

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ferry.player.ResumeRule
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import app.ferry.ui.nav.Screen
import app.ferry.ui.tv.TvPlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TV suite class 3 (plan §4.14) [T2] [A12]: play `big.mp4` to about 40 s (D-pad Right ×4), Back; the position is
 * saved; OK on the same card → "Resume from 0:4x" with the focus on Resume (app state, B69); one OK → playback goes
 * on at about that position. Then Back, reopen, Left → "Start over", OK → playback from the start.
 */
@RunWith(AndroidJUnit4::class)
class TvResumeTest {
    @Test
    fun reopenOffersResumeWithFocusOnResume() {
        LaunchHelpers.cleanPrefs(welcomeDone = false)
        TvHelpers.leaveTouchMode()
        LaunchHelpers.launch().use { sc ->
            SendHelpers.waitConnected()
            val t = TvHelpers(sc)
            t.waitOutbox("big.mp4")
            t.focusCard("videos", "big.mp4")
            val card = t.current!!
            val big = t.vm.outbox.value!!.files.first { it.name == "big.mp4" }
            val key = ResumeRule.key(big.name, big.size, big.mtime)
            t.ok("player shown") { it is Screen.TvPlayer }
            assertEquals("first open: no resume question", null, TvPlayerState.resumeOffer)
            val host = checkNotNull(TvPlayerState.host)
            fun pos() = t.main { host.player.currentPosition }
            fun playing() = t.main { host.player.isPlaying }
            SendHelpers.waitUntil("playing", 60_000) { playing() }

            repeat(4) {
                val before = pos()
                SendHelpers.device.pressDPadRight()
                SendHelpers.waitUntil("+10 s #${it + 1}", 5_000) { pos() >= before + 8_000 }
            }
            SendHelpers.waitUntil("about 40 s in and playing", 30_000) { pos() >= 40_000 && playing() }
            Thread.sleep(1_000)
            val left = pos()
            SendHelpers.device.pressBack()
            SendHelpers.waitUntil("home again", 10_000) { t.screen() is Screen.TvHome }
            val saved = checkNotNull(AppGraph.resume.get(key)) { "position not saved for $key" }
            // UiAutomator's pressBack waits for an idle UI, which a playing video delays, so the player closes a few
            // seconds after `left`: the store must hold the position at the moment the screen closed.
            val closedAt = checkNotNull(TvPlayerState.closedAtMs)
            Probe.record("resume_saved", "left at $left ms, closed at $closedAt ms, saved ${saved.posMs} ms of ${saved.durMs} ms")
            assertTrue("closed after we pressed Back", closedAt >= left - 500)
            assertTrue("saved position = where the player closed", saved.posMs in (closedAt - 500)..(closedAt + 500))
            t.waitFocus("focus back on big.mp4") { t.current == card }

            // Reopen: the question, Resume focused; one OK continues there.
            t.ok("player again") { it is Screen.TvPlayer }
            SendHelpers.waitUntil("resume offered", 10_000) { TvPlayerState.resumeOffer != null }
            assertEquals(saved.posMs, TvPlayerState.resumeOffer)
            val label = Probe.context.getString(R.string.resume_from, ResumeRule.clock(saved.posMs))
            SendHelpers.waitText(label, 10_000)
            SendHelpers.waitUntil("Resume has focus", 10_000) { TvPlayerState.resumeFocused }
            SendHelpers.settle()
            Shots.take("tv-resume-dialog")
            val host2 = checkNotNull(TvPlayerState.host)
            assertTrue("waits for the answer", t.main { !host2.player.playWhenReady })
            SendHelpers.device.pressDPadCenter()
            SendHelpers.waitUntil("playing after Resume", 15_000) { t.main { host2.player.isPlaying } }
            val at = t.main { host2.player.currentPosition }
            Probe.record("resume_playing_at", "$at ms (saved ${saved.posMs})")
            assertTrue("resumed near the saved position", at in (saved.posMs - 1_000)..(saved.posMs + 8_000))
            Thread.sleep(2_000)
            SendHelpers.device.pressBack()
            SendHelpers.waitUntil("home again", 10_000) { t.screen() is Screen.TvHome }
            t.waitFocus("focus back on big.mp4") { t.current == card }

            // "Start over": Left from Resume, OK → from the start.
            t.ok("player a third time") { it is Screen.TvPlayer }
            SendHelpers.waitUntil("resume offered again", 10_000) { TvPlayerState.resumeOffer != null && TvPlayerState.resumeFocused }
            SendHelpers.device.pressDPadLeft()
            SendHelpers.waitUntil("Resume lost focus to Start over", 5_000) { !TvPlayerState.resumeFocused }
            SendHelpers.device.pressDPadCenter()
            val host3 = checkNotNull(TvPlayerState.host)
            SendHelpers.waitUntil("playing after Start over", 15_000) { t.main { host3.player.isPlaying } }
            val from = t.main { host3.player.currentPosition }
            Probe.record("start_over_playing_at", "$from ms")
            assertTrue("started over", from < 8_000)
        }
    }
}
