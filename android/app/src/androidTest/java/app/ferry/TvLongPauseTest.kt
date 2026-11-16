package app.ferry

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import app.ferry.ui.nav.Screen
import app.ferry.ui.tv.TvPlayerState
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TV suite class 2 (plan §4.14) [T2] [B13] [acc 5], replaces the Ph0b `LongPauseProbe` with the product screen: OK on
 * `big.mp4` (300 s at a constant 6 Mbit/s) opens the player; after 5 s of playback it is paused with the remote's
 * Play/Pause key, with less than the whole file buffered (else the pause proves nothing [PR7]); 90 s later (> the
 * PC's 60 s idle timeout) OK resumes it: playing within 10 s, and it plays on past everything that was buffered
 * before and during the pause, so a new connection to the PC was needed. Then Fast-forward (+30 s), Rewind (−30 s)
 * and D-pad Right (+10 s): position moves and it keeps playing. No `PlaybackException` at any point.
 */
@RunWith(AndroidJUnit4::class)
class TvLongPauseTest {
    @Test
    fun reconnectsAfterLongPauseAndSeeks() {
        LaunchHelpers.cleanPrefs(welcomeDone = false)
        TvHelpers.leaveTouchMode()
        LaunchHelpers.launch().use { sc ->
            SendHelpers.waitConnected()
            val t = TvHelpers(sc)
            t.waitOutbox("big.mp4")
            t.focusCard("videos", "big.mp4")
            t.ok("player shown") { it is Screen.TvPlayer }
            val host = checkNotNull(TvPlayerState.host)
            fun pos() = t.main { host.player.currentPosition }
            fun playing() = t.main { host.player.isPlaying }
            fun noError() = assertNull("player error: ${host.playbackError}", host.playbackError)

            SendHelpers.waitUntil("5 s of playback", 60_000) { host.playbackError != null || (playing() && pos() >= 5_000) }
            noError()

            t.key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            SendHelpers.waitUntil("paused by Play/Pause", 5_000) { t.main { !host.player.playWhenReady } }
            Thread.sleep(500)
            val (atPause, bufferedAtPause, duration) = t.main {
                Triple(host.player.currentPosition, host.player.bufferedPosition, host.player.duration)
            }
            Probe.record("long_pause_at_pause", "pos=$atPause buffered=$bufferedAtPause duration=$duration loads=${host.loadsStarted.get()}")
            assertTrue("whole file buffered before the pause: the pause proves nothing", bufferedAtPause < duration)
            SendHelpers.settle()
            Shots.take("tv-player-paused")

            Thread.sleep(90_000)
            val bufferedAfter = t.main { host.player.bufferedPosition }
            Probe.record("long_pause_after_90s", "buffered=$bufferedAfter loads=${host.loadsStarted.get()} loadErrors=${host.loadErrors.get()}")

            val resumedAt = System.currentTimeMillis()
            SendHelpers.device.pressDPadCenter()
            SendHelpers.waitUntil("playing within 10 s of OK", 10_000) { playing() }
            Probe.record("long_pause_resumed_ms", System.currentTimeMillis() - resumedAt)
            val target = maxOf(bufferedAtPause, bufferedAfter) + 10_000
            SendHelpers.waitUntil("play past the pre-pause buffer ($target ms)", (target - atPause) + 60_000) {
                host.playbackError != null || pos() >= target
            }
            noError()
            Probe.record("long_pause_result", "pos=${pos()} target=$target loads=${host.loadsStarted.get()} loadErrors=${host.loadErrors.get()}")

            // Fast-forward / Rewind (±30 s) and D-pad Right (+10 s): position moves, playback goes on.
            seek(t, "fast-forward", KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, 30_000, ::pos, ::playing)
            seek(t, "rewind", KeyEvent.KEYCODE_MEDIA_REWIND, -30_000, ::pos, ::playing)
            seek(t, "d-pad right", KeyEvent.KEYCODE_DPAD_RIGHT, 10_000, ::pos, ::playing)
            seek(t, "d-pad left", KeyEvent.KEYCODE_DPAD_LEFT, -10_000, ::pos, ::playing)
            noError()
            SendHelpers.settle()
            Shots.take("tv-player-after-long-pause")
            Probe.record("long_pause_done", "pos=${pos()} loadErrors=${host.loadErrors.get()} error=${host.playbackError}")
        }
    }

    /** One key press must move the position by about [deltaMs] (± 6 s of playback/rounding) and keep playing. */
    private fun seek(t: TvHelpers, what: String, code: Int, deltaMs: Long, pos: () -> Long, playing: () -> Boolean) {
        val before = pos()
        t.key(code)
        val want = before + deltaMs
        SendHelpers.waitUntil("$what: position near $want (was $before)", 5_000) { pos() in (want - 6_000)..(want + 6_000) }
        SendHelpers.waitUntil("$what: playing again", 10_000) { playing() }
        Probe.record("seek_$what", "$before → ${pos()}")
    }
}
