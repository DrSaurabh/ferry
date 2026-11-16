package app.ferry

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import app.ferry.net.ConnectionState
import app.ferry.net.UpdateState
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import app.ferry.ui.nav.Screen
import app.ferry.ui.tv.KeepScreenOn
import app.ferry.ui.tv.RowFocus
import app.ferry.ui.tv.TvPlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TV suite class 1 (plan §4.14) [T1] [A13] [PA14] [PB20], replaces Ph4's `TvLaunchTest`: TV mode; the first Videos
 * card has focus; every D-pad press moves exactly one card; one OK press is exactly one click; `old.avi` is under
 * Other files [A12]; OK on video 2 opens the Player (Ph8a: the interim probe-stack player) and Back puts the focus
 * back on video 2; the update banner "Ferry 9.9.9 is available" is shown and takes focus (C3 on the TV); no "Send"
 * anywhere (T6); the screen is kept on. Remote = `UiDevice.pressDPad*`; focus and screens are read from app state
 * (no Compose test rule while Home refreshes from IO, B50).
 */
@RunWith(AndroidJUnit4::class)
class TvLaunchAndFocusTest {
    private val ctx get() = Probe.context

    @Test
    fun remoteMovesOneCardPerPressAndFocusComesBack() {
        LaunchHelpers.cleanPrefs(welcomeDone = false) // the TV never shows Welcome
        TvHelpers.leaveTouchMode()
        assertTrue("android-tv image must be in TV mode", DeviceMode.isTv(Probe.context, null))
        LaunchHelpers.launch().use { sc ->
            SendHelpers.waitConnected()
            val c = AppGraph.connection.state.value as ConnectionState.Connected
            Probe.record("tv_launch", "connected name=${c.name} network=${AppGraph.network.chosen.value?.kind}")
            val t = TvHelpers(sc)
            val expected = listOf("big.mp4", "small.mp4", "photo1.jpg", "broken.jpg", "Test.apk", "notes.txt", "old.avi")
            SendHelpers.waitUntil("outbox listed", 30_000) {
                val names = t.vm.outbox.value?.files?.map { it.name }.orEmpty()
                expected.all { it in names }
            }
            Probe.record("tv_outbox", t.vm.outbox.value?.files?.joinToString { "${it.name}:${it.kind}" })
            assertEquals("video", t.vm.outbox.value!!.files.first { it.name == "old.avi" }.kind)

            // First visit: the first card of the first non-empty row (Videos) has focus.
            t.waitFocus("first Videos card focused", 20_000) { t.current == RowFocus("videos", 0) }
            SendHelpers.waitUntil("update offered", 30_000) { AppGraph.update.value is UpdateState.Available }
            val info = (AppGraph.update.value as UpdateState.Available).info
            val bannerText = ctx.getString(R.string.update_available, info.versionName)
            SendHelpers.waitText(bannerText)
            SendHelpers.waitText(ctx.getString(R.string.tv_ready))
            SendHelpers.settle()
            Shots.take("tv-home")

            // One press = one card [PA14].
            t.press("right") { it == RowFocus("videos", 1) }
            t.press("down") { it?.row == "photos" }
            val p = t.current!!.index
            t.press("right") { it == RowFocus("photos", p + 1) }
            t.press("right") { it == RowFocus("photos", p + 2) }
            t.press("left") { it == RowFocus("photos", p + 1) }
            Thread.sleep(1_500) // thumbnails of the cards on screen
            SendHelpers.settle()
            Shots.take("tv-home-photos")
            t.press("down") { it?.row == "apps" }
            t.press("down") { it?.row == "other" }
            if (t.title != "old.avi") {
                val i = t.current!!.index
                if (i == 0) t.press("right") { it == RowFocus("other", 1) } else t.press("left") { it == RowFocus("other", i - 1) }
            }
            assertEquals("old.avi is under Other files [A12]", "old.avi", t.title)
            assertEquals("other", t.current?.row)

            // One OK = one click (an Other files card does nothing else) [PA14].
            val c0 = t.vm.clicks.get()
            SendHelpers.device.pressDPadCenter()
            SendHelpers.waitUntil("click counted", 5_000) { t.vm.clicks.get() > c0 }
            Thread.sleep(1_500)
            Probe.record("clicks_after_one_ok", t.vm.clicks.get() - c0)
            assertEquals("one OK press must give exactly one click", c0 + 1, t.vm.clicks.get())
            SendHelpers.settle()
            Shots.take("tv-home-other-files")

            // Back up to the Videos row, card 2.
            t.press("up") { it?.row == "apps" }
            t.press("up") { it?.row == "photos" }
            t.press("up") { it?.row == "videos" }
            while (t.current!!.index > 1) {
                val i = t.current!!.index
                t.press("left") { it == RowFocus("videos", i - 1) }
            }
            if (t.current!!.index == 0) t.press("right") { it == RowFocus("videos", 1) }
            val video2 = t.title
            Probe.record("video_2", video2)

            // OK on video 2 → Player (exactly one screen pushed).
            val c1 = t.vm.clicks.get()
            SendHelpers.device.pressDPadCenter()
            SendHelpers.waitUntil("player shown", 10_000) { t.screen() is Screen.TvPlayer }
            Thread.sleep(1_500)
            assertEquals(c1 + 1, t.vm.clicks.get())
            assertEquals("one OK = one Player on the back stack", 2, t.stackSize())
            assertEquals(video2, (t.screen() as Screen.TvPlayer).name)
            var playing = false
            val end = System.currentTimeMillis() + 15_000
            while (!playing && System.currentTimeMillis() < end) {
                Probe.instrumentation.runOnMainSync { playing = TvPlayerState.player?.isPlaying == true }
                if (!playing) Thread.sleep(250)
            }
            Probe.record("player_playing", playing) // asserted by TvLongPauseTest / TvResumeTest (Ph8b)
            var keptOn = false
            sc.onActivity { a -> keptOn = KeepScreenOn.isOn(a) }
            assertTrue("screen kept on while the player shows", keptOn)
            // Up shows the display-only controller (Ph8b: media3 PlayerView under the platform theme [PA18]).
            SendHelpers.device.pressDPadUp()
            SendHelpers.waitUntil("controller shown", 5_000) { TvPlayerState.controllerVisible }
            Thread.sleep(300)
            Shots.take("tv-player-controller")
            assertEquals("Up must not leave the player", 2, t.stackSize())

            // Back → Home with the focus on the same card [A13].
            SendHelpers.device.pressBack()
            SendHelpers.waitUntil("home again", 10_000) { t.screen() is Screen.TvHome }
            t.waitFocus("focus back on video 2") { t.current == RowFocus("videos", 1) }
            Probe.record("focus_after_back", "${t.current} '${t.title}'")
            assertEquals(video2, t.title)
            SendHelpers.settle()
            Shots.take("tv-home-back-from-player")

            // The update banner takes focus (above the first row).
            SendHelpers.device.pressDPadUp()
            SendHelpers.waitUntil("banner focused", 5_000) { t.vm.bannerFocused }
            assertEquals(null, t.current)
            SendHelpers.settle()
            Shots.take("tv-home-banner-focused")

            // T6: the TV does not send files [PB20]; the screen stays on at Home after a key press.
            assertTrue("no 'Send' on the TV", SendHelpers.device.findObjects(By.textContains("Send")).isEmpty())
            sc.onActivity { a -> keptOn = KeepScreenOn.isOn(a) }
            assertTrue("screen kept on at Home after a key press", keptOn)
        }
    }
}
