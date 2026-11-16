package app.ferry

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import app.ferry.cast.CastPhase
import app.ferry.cast.CastProblem
import app.ferry.core.HostPort
import app.ferry.core.model.CastState
import app.ferry.media.PickerMode
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import app.ferry.ui.nav.Screen
import app.ferry.ui.phone.CastViewModel
import app.ferry.ui.tv.CastReceiverHost
import app.ferry.ui.tv.CastSinkImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phone suite class 9 (plan §4.14) [P9] [T5] [B27]: the TV's receiver runs in this same app (debug extra
 * `CAST_RECEIVER`, on 127.0.0.1:8766), so the whole cast path runs on one emulator with no PC: Play on TV finds the
 * remembered TV → Videos → the picker (one video) → the phone's file server + CastService → the "TV" plays it; the
 * Remote's Pause / +10 s / Play reach the TV; the notification opens the Remote [A24]; Stop ends both sides. Then
 * 3 photos → the slideshow advances, Next and the interval reach the TV, and Back on the TV ends the phone's session.
 *
 * The "TV" state is read from the receiver ([CastSinkImpl.snapshot], what `GET /api/v1/state` answers) and the phone's
 * view of it from [app.ferry.cast.Casting.session]; taps go through UiAutomator (no Compose test rule while state
 * streams in, B50). Media: `push_cast_media` (a 60 s video, B79, and three photos made by the shell).
 */
@RunWith(AndroidJUnit4::class)
class CastLoopbackTest {
    private val ctx get() = Probe.context
    private val tv: CastState get() = CastSinkImpl.snapshot
    private val phone get() = AppGraph.casting.session.value

    private fun str(id: Int) = ctx.getString(id)

    private fun tapDesc(desc: String) {
        val o = SendHelpers.device.wait(Until.findObject(By.desc(desc)), 10_000)
        checkNotNull(o) { "no '$desc' on screen" }.click()
    }

    private fun screen(sc: ActivityScenario<MainActivity>): Screen? {
        var s: Screen? = null
        sc.onActivity { s = it.nav?.current }
        return s
    }

    private fun castVm(sc: ActivityScenario<MainActivity>): CastViewModel {
        var vm: CastViewModel? = null
        sc.onActivity { vm = ViewModelProvider(it)[CastViewModel::class.java] }
        return checkNotNull(vm)
    }

    private fun record(what: String) = Probe.record("cast_$what", "tv=$tv phone=${phone?.phase}/${phone?.problem} ${phone?.tvState}")

    @Test
    fun videoAndSlideshowThroughTheLoopback() {
        LaunchHelpers.cleanPrefs(welcomeDone = true)
        val loopback = HostPort("127.0.0.1", CastReceiverHost.PORT)
        AppGraph.prefs.lastTv = loopback // the remembered TV [A24] (broadcasts can't be tested on the emulator, B27)
        AppGraph.prefs.slideshowSeconds = 3
        val i = Intent(ctx, MainActivity::class.java).putExtra(DeviceMode.EXTRA_CAST_RECEIVER, true)
        ActivityScenario.launch<MainActivity>(i).use { sc ->
            SendHelpers.waitUntil("cast receiver listening", 15_000) { CastReceiverHost.listening }
            SendHelpers.waitUntil("Home", 10_000) { screen(sc) == Screen.Home }

            // ---- Play on TV: the remembered TV answers and is used at once.
            val vm = castVm(sc)
            var finds = vm.findsDone
            SendHelpers.tap(str(R.string.btn_play_tv))
            SendHelpers.waitUntil("remembered TV chosen", 15_000) { vm.findsDone > finds && vm.chosen.value != null }
            assertEquals(loopback, vm.chosen.value!!.hp)
            SendHelpers.waitText(str(R.string.cast_videos))
            SendHelpers.settle()
            Shots.take("play-on-tv")

            // ---- Videos → picker (videos only, one) → Play on TV → Remote.
            SendHelpers.tap(str(R.string.cast_videos))
            SendHelpers.waitUntil("video picker", 10_000) { screen(sc) == Screen.Picker(PickerMode.CastVideo) }
            SendHelpers.clickDesc("CAST_60s.mp4")
            SendHelpers.tap(str(R.string.picker_play))
            SendHelpers.waitUntil("Remote on screen", 15_000) { screen(sc) == Screen.Remote }
            SendHelpers.waitUntil("TV plays the video (kind video, playing)", 10_000) { tv.kind == "video" && tv.playing }
            record("started")
            assertEquals("CAST_60s.mp4", tv.title)
            SendHelpers.waitUntil("the video's position advances on the TV", 20_000) { tv.positionMs > 1_500 && tv.durationMs > 50_000 }
            SendHelpers.waitUntil("the phone sees it playing", 10_000) { phone?.phase == CastPhase.Playing && phone?.tvState?.playing == true }
            assertTrue("the service runs in the foreground (mediaPlayback)",
                SendHelpers.shell("dumpsys activity services app.ferry/.service.CastService").contains("isForeground=true"))
            SendHelpers.settle()
            Shots.take("remote-video")

            // ---- Pause from the phone.
            tapDesc(str(R.string.remote_toggle))
            SendHelpers.waitUntil("paused on the TV", 5_000) { !tv.playing }
            val pausedAt = tv.positionMs
            Thread.sleep(2_000)
            assertTrue("position stays while paused: $pausedAt → ${tv.positionMs}", kotlin.math.abs(tv.positionMs - pausedAt) < 700)
            SendHelpers.waitUntil("the phone shows Paused", 5_000) { phone?.tvState?.playing == false }
            SendHelpers.settle()
            Shots.take("remote-video-paused")
            record("paused")

            // ---- +10 s, then Play.
            tapDesc(str(R.string.remote_fwd10))
            SendHelpers.waitUntil("+10 s on the TV", 5_000) { tv.positionMs >= pausedAt + 9_000 }
            tapDesc(str(R.string.remote_toggle))
            SendHelpers.waitUntil("playing again", 5_000) { tv.playing }
            val resumedAt = tv.positionMs
            SendHelpers.waitUntil("advances after Play", 10_000) { tv.positionMs > resumedAt + 1_000 }
            record("seeked_and_playing")

            // ---- The casting notification opens the Remote [A24].
            sc.onActivity { it.nav!!.replaceAll(Screen.Home) }
            SendHelpers.waitUntil("Home again", 5_000) { screen(sc) == Screen.Home }
            val d = SendHelpers.device
            d.openNotification()
            val n = d.wait(Until.findObject(By.pkg("com.android.systemui").textContains(str(R.string.cast_playing_on).substringBefore("%"))), 10_000)
            assertNotNull("casting notification in the shade", n)
            Probe.record("cast_notification", n.text)
            SendHelpers.settle()
            Shots.take("cast-notification")
            n.click()
            SendHelpers.waitUntil("notification tap → Remote", 10_000) { screen(sc) == Screen.Remote }

            // ---- Stop: the TV goes idle, the session ends, the service stops.
            tapDesc(str(R.string.remote_stop))
            SendHelpers.waitUntil("TV idle after Stop", 10_000) { CastSinkImpl.show.value == null && tv.kind == CastState.IDLE }
            SendHelpers.waitUntil("phone session ended", 10_000) { phone?.phase == CastPhase.Ended }
            assertEquals("stopped with Stop: no problem", null, phone?.problem)
            assertTrue(CastSinkImpl.received.any { it == "control stop" })
            SendHelpers.waitUntil("CastService gone", 10_000) {
                !SendHelpers.shell("dumpsys activity services app.ferry/.service.CastService").contains("ServiceRecord")
            }
            assertEquals("the TV is remembered", loopback, AppGraph.prefs.lastTv)
            record("stopped")

            // ---- Photos ×3 → slideshow (3 s from the phone's setting) advances; Next and 10 s reach the TV.
            sc.onActivity { it.nav!!.replaceAll(Screen.Home) }
            finds = vm.findsDone
            SendHelpers.tap(str(R.string.btn_play_tv))
            SendHelpers.waitUntil("TV chosen again", 15_000) { vm.findsDone > finds && vm.chosen.value != null }
            SendHelpers.tap(str(R.string.cast_photos))
            SendHelpers.waitUntil("photo picker", 10_000) { screen(sc) == Screen.Picker(PickerMode.CastPhotos) }
            for (name in listOf("CAST_1.jpg", "CAST_2.jpg", "CAST_3.jpg")) SendHelpers.clickDesc(name)
            SendHelpers.tap(str(R.string.picker_play))
            SendHelpers.waitUntil("Remote on screen", 15_000) { screen(sc) == Screen.Remote }
            SendHelpers.waitUntil("TV shows the slideshow", 10_000) { tv.kind == "photos" && tv.count == 3 && tv.playing }
            assertEquals(3_000L, tv.intervalMs)
            val first = tv.index
            SendHelpers.waitUntil("the slideshow advances by itself", 8_000) { tv.index != first }
            record("slideshow_advanced")
            SendHelpers.waitUntil("the phone sees the slideshow", 5_000) { phone?.tvState?.kind == "photos" }
            SendHelpers.settle()
            Shots.take("remote-photos")
            val before = tv.index
            tapDesc(str(R.string.remote_next))
            SendHelpers.waitUntil("Next → next photo", 3_000) { tv.index == (before + 1) % 3 }
            tapDesc(ctx.getString(R.string.remote_interval_s, 10))
            SendHelpers.waitUntil("interval 10 s on the TV", 5_000) { tv.intervalMs == 10_000L }
            tapDesc(str(R.string.remote_toggle))
            SendHelpers.waitUntil("slideshow paused", 5_000) { !tv.playing }
            record("photos_controls")

            // ---- Back on the TV ends the phone's session by itself ("Stopped on the TV").
            Probe.instrumentation.runOnMainSync { CastSinkImpl.stop("Back on the TV (test)") }
            SendHelpers.waitUntil("phone notices the TV stopped", 15_000) { phone?.phase == CastPhase.Ended }
            assertEquals(CastProblem.StoppedOnTv, phone?.problem)
            SendHelpers.waitText(str(R.string.cast_stopped_on_tv), 5_000)
            SendHelpers.settle()
            Shots.take("remote-stopped-on-tv")
            assertFalse("no cast left on the TV", CastSinkImpl.show.value != null)
            Probe.record("cast_received", CastSinkImpl.received.toList())
        }
    }
}
