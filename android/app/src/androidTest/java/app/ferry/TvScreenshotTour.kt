package app.ferry

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ferry.net.UpdateState
import app.ferry.probe.Shots
import app.ferry.ui.nav.Screen
import app.ferry.ui.tv.TvPlayerState
import app.ferry.ui.tv.TvViewerState
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TV suite class 7 (plan §4.14), Ph8b part: screenshots of home with the update banner, the player with its
 * controller (playing, then paused), the viewer and the slideshow overlay. Ph10 completes the tour (empty home,
 * install dialog, PC not found; those screens are also shot by TvInstallTest / TvPcNotFoundTest).
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

            // Player: playing with the controller, then paused.
            t.focusCard("videos", "small.mp4")
            t.ok("player") { it is Screen.TvPlayer }
            val host = checkNotNull(TvPlayerState.host)
            SendHelpers.waitUntil("playing", 30_000) { t.main { host.player.isPlaying } }
            Thread.sleep(1_500)
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
        }
    }
}
