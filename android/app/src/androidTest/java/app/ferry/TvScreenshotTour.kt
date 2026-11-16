package app.ferry

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ferry.core.HostPort
import app.ferry.net.ConnectionState
import app.ferry.net.UpdateState
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import app.ferry.ui.nav.Screen
import app.ferry.ui.tv.TvPlayerState
import app.ferry.ui.tv.TvViewerState
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TV suite class 7 (plan §4.14): screenshots of home with the update banner, the player with its controller
 * (playing, then paused mid-video), the viewer and the slideshow overlay; Ph10 adds "not on Wi-Fi" / "PC not found"
 * in the TV's words and the empty home (PC-E). The install dialog and the PC-not-found screen with its buttons are
 * shot by TvInstallTest / TvPcNotFoundTest.
 */
@RunWith(AndroidJUnit4::class)
class TvScreenshotTour {
    @Test
    fun tour() {
        LaunchHelpers.cleanPrefs(welcomeDone = false)
        AppGraph.prefs.slideshowSeconds = 10
        TvHelpers.leaveTouchMode()
        LaunchHelpers.launch().use { sc ->
            SendHelpers.waitConnected()
            val t = TvHelpers(sc)
            t.waitOutbox("small.mp4", "photo1.jpg")
            SendHelpers.waitUntil("update offered", 30_000) { AppGraph.update.value is UpdateState.Available }
            t.waitFocus("a card focused", 20_000) { t.current != null }
            SendHelpers.settle()
            Shots.take("tour-home")

            // Player: playing with the controller, then paused mid-video (Ph10: big.mp4, 5 min, not the 6 s small.mp4).
            t.focusCard("videos", "big.mp4")
            t.ok("player") { it is Screen.TvPlayer }
            val host = checkNotNull(TvPlayerState.host)
            SendHelpers.waitUntil("playing", 30_000) { t.main { host.player.isPlaying } }
            SendHelpers.waitUntil("8 s in", 30_000) { t.main { host.player.currentPosition } > 8_000 }
            SendHelpers.device.pressDPadUp()
            SendHelpers.waitUntil("controller", 5_000) { TvPlayerState.controllerVisible }
            Thread.sleep(400)
            Shots.take("tour-player-controller")
            t.key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            SendHelpers.waitUntil("paused", 5_000) { t.main { !host.player.playWhenReady } }
            Thread.sleep(600)
            Shots.take("tour-player-paused")
            SendHelpers.device.pressBack()
            SendHelpers.waitUntil("home", 10_000) { t.screen() is Screen.TvHome }

            // Viewer on photo1.jpg, then the slideshow overlay after Right.
            t.focusCard("photos", "photo1.jpg")
            t.ok("viewer") { it is Screen.TvViewer }
            SendHelpers.waitUntil("photo1 shown", 10_000) { "photo1.jpg" in TvViewerState.shown }
            Thread.sleep(2_500) // the overlay hides after 2 s
            Shots.take("tour-viewer")
            SendHelpers.device.pressDPadCenter() // starts the slideshow from here
            SendHelpers.waitUntil("slideshow playing", 3_000) { TvViewerState.playing }
            SendHelpers.device.pressDPadRight()
            SendHelpers.waitUntil("overlay", 3_000) { TvViewerState.overlayVisible }
            Thread.sleep(600)
            Shots.take("tour-slideshow-overlay")
            SendHelpers.device.pressBack()
            SendHelpers.waitUntil("home", 10_000) { t.screen() is Screen.TvHome }

            // Ph10: "not on Wi-Fi" and "PC not found" as the TV words them ("this TV", not "this phone").
            val conn = AppGraph.connection
            for ((name, st) in listOf("tour-tv-no-wifi" to ConnectionState.NoWifi, "tour-tv-not-found" to ConnectionState.NotFound(emptyList()))) {
                Probe.instrumentation.runOnMainSync { conn.showForTest(st) }
                SendHelpers.waitUntil(name, 5_000) { conn.state.value == st }
                SendHelpers.settle()
                Shots.take(name)
            }

            // Ph10: the empty home (PC-E has an empty outbox).
            AppGraph.prefs.manual = HostPort.parse(Probe.arg("pcE", "10.0.2.2:18768"))
            conn.refresh()
            SendHelpers.waitUntil("connected to PC-E", 30_000) {
                (conn.state.value as? ConnectionState.Connected)?.api?.base?.toString()?.endsWith(":18768") == true
            }
            SendHelpers.waitUntil("empty outbox", 30_000) { t.vm.outbox.value?.files?.isEmpty() == true }
            SendHelpers.settle()
            Shots.take("tour-home-empty")
        }
    }
}
