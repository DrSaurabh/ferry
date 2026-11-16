package app.ferry

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import app.ferry.ui.common.MediaAccess
import app.ferry.ui.common.mediaAccess
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phone suite class 3b (plan §4.14) [A15]: runs first of the media classes, on the fresh install with only
 * READ_MEDIA_VISUAL_USER_SELECTED granted (Android 14 "Select photos"). "Send photos & videos" opens the picker
 * straight away (no system dialog) and the picker shows "Ferry can only see some photos → Allow all"; so does Home.
 * UiAutomator only (no Compose test rule, B50).
 */
@RunWith(AndroidJUnit4::class)
class PartialAccessBannerTest {
    @Test
    fun partialAccessShowsTheAllowAllBanner() {
        LaunchHelpers.cleanPrefs(welcomeDone = true)
        val access = mediaAccess(Probe.context)
        Probe.record("media_access", access)
        assertEquals("host must grant only READ_MEDIA_VISUAL_USER_SELECTED", MediaAccess.Partial, access)
        LaunchHelpers.launch().use {
            SendHelpers.waitConnected()
            SendHelpers.waitText("Ferry can only see some photos") // Home banner
            SendHelpers.tap("Send photos & videos")
            SendHelpers.waitText("Albums")
            SendHelpers.waitText("Ferry can only see some photos") // picker banner
            SendHelpers.settle()
            Shots.take("picker-partial-access")
            Probe.record("partial_banner", "shown on Home and in the picker")
        }
    }
}
