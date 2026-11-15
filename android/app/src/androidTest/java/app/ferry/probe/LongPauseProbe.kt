package app.ferry.probe

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ferry.player.FerryLoadErrorPolicy
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Ph0b, TV image: does the product's player stack (ExoPlayer + OkHttpDataSource with connect 8 s /
 * read 20 s + FerryLoadErrorPolicy + the §4.10 buffer sizes) [CP2] [PA5] carry on after a 90 s pause,
 * longer than the PC's 60 s idle timeout? Plays big.mp4 (300 s, 6 Mbit/s) from v1 ferry.py: 5 s, pause
 * 90 s, resume, then keeps playing past the position that was buffered before the pause, so data had
 * to be fetched again after the PC dropped the idle connection.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class LongPauseProbe {
    private lateinit var player: ExoPlayer
    private val error = AtomicReference<PlaybackException?>(null)
    private val loadErrors = AtomicInteger(0)
    private val loadsStarted = AtomicInteger(0)

    private fun <T> main(block: () -> T): T {
        val r = AtomicReference<T>()
        Probe.instrumentation.runOnMainSync { r.set(block()) }
        return r.get()
    }

    private fun pos() = main { player.currentPosition }
    private fun playing() = main { player.isPlaying }

    private fun waitFor(what: String, timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (error.get() != null) return false
            if (cond()) return true
            Thread.sleep(250)
        }
        Probe.record("long_pause_timeout", what)
        return false
    }

    @Test
    fun reconnectsAfterLongPause() {
        val files = JSONObject(Probe.get("/api/v1/outbox")).getJSONArray("files")
        val url = (0 until files.length()).map { files.getJSONObject(it) }
            .first { it.getString("name") == "big.mp4" }.getString("url")
        val client = Probe.http.newBuilder().build() // connect 8 s, read 20 s (Probe.http)
        main {
            val sources = DefaultMediaSourceFactory(OkHttpDataSource.Factory(client))
                .setLoadErrorHandlingPolicy(FerryLoadErrorPolicy)
            val load = DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 2_500, 5_000).build()
            player = ExoPlayer.Builder(Probe.context).setMediaSourceFactory(sources).setLoadControl(load).build()
            player.addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    error.set(e)
                }
            })
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onLoadStarted(t: AnalyticsListener.EventTime, l: LoadEventInfo, d: MediaLoadData) {
                    loadsStarted.incrementAndGet()
                }
                override fun onLoadError(
                    t: AnalyticsListener.EventTime, l: LoadEventInfo, d: MediaLoadData, e: IOException, cancelled: Boolean,
                ) {
                    loadErrors.incrementAndGet()
                    Probe.record("long_pause_load_error", "${e.javaClass.name}: ${e.message} (cause ${e.cause})")
                }
            })
            player.setMediaItem(MediaItem.fromUri("http://${Probe.pc}$url"))
            player.prepare()
            player.play()
        }
        try {
            // Emulators started with -noaudio may not advance the audio clock; if playback is stuck,
            // retry with the audio track disabled (the network behaviour is the same: one muxed file).
            var advanced = waitFor("first 5 s of playback", 45_000) { playing() && pos() >= 5_000 }
            if (!advanced && error.get() == null) {
                Probe.record("long_pause_audio_clock", "stuck at ${pos()} ms; disabling audio")
                main {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                    player.seekTo(0)
                }
                advanced = waitFor("5 s of playback without audio", 45_000) { playing() && pos() >= 5_000 }
            } else {
                Probe.record("long_pause_audio_clock", "ok")
            }
            assertNull("player error before pause: ${error.get()}", error.get())
            assertTrue("playback never reached 5 s", advanced)

            val (atPause, bufferedAtPause, duration) = main {
                player.pause(); Triple(player.currentPosition, player.bufferedPosition, player.duration)
            }
            Probe.record("long_pause_at_pause", "pos=$atPause buffered=$bufferedAtPause duration=$duration loads=${loadsStarted.get()}")
            assertTrue("whole file buffered before the pause: the pause proves nothing", bufferedAtPause < duration)

            Thread.sleep(90_000)
            val bufferedAfter = main { player.bufferedPosition }
            Probe.record("long_pause_after_90s", "buffered=$bufferedAfter loads=${loadsStarted.get()} errors=${loadErrors.get()}")

            val resumedAt = System.currentTimeMillis()
            main { player.play() }
            assertTrue("not playing within 10 s of resume", waitFor("resume", 10_000) { playing() })
            Probe.record("long_pause_resumed_ms", System.currentTimeMillis() - resumedAt)
            // Play past what was buffered before/while paused: needs a new connection to the PC.
            val target = maxOf(bufferedAtPause, bufferedAfter) + 10_000
            val ok = waitFor("play past buffered position $target", (target - atPause) + 60_000) { pos() >= target }
            Probe.record("long_pause_result",
                "ok=$ok pos=${pos()} target=$target loads=${loadsStarted.get()} loadErrors=${loadErrors.get()} error=${error.get()?.errorCodeName}")
            assertNull("player error after resume: ${error.get()}", error.get())
            assertTrue("playback did not get past the pre-pause buffer", ok)
        } finally {
            main { player.release() }
        }
    }
}
