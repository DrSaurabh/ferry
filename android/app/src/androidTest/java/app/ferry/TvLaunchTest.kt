package app.ferry

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ferry.net.ConnectionState
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TV suite (plan §6 Ph4; replaced by TvLaunchAndFocusTest in Ph8a [PB20]): TV mode on the android-tv image,
 * the header shows the PC, the outbox rows list PC-T's files; no "Send" button in TV mode (T6).
 */
@RunWith(AndroidJUnit4::class)
class TvLaunchTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun tvHomeShowsPcAndOutbox() {
        LaunchHelpers.cleanPrefs(welcomeDone = false) // the TV never shows Welcome
        assertTrue("android-tv image must be in TV mode", DeviceMode.isTv(Probe.context, null))
        LaunchHelpers.launch().use {
            compose.waitUntil(30_000) { AppGraph.connection.state.value is ConnectionState.Connected }
            val c = AppGraph.connection.state.value as ConnectionState.Connected
            LaunchHelpers.waitForText(compose, "Ferry · ${c.name}")
            LaunchHelpers.waitForText(compose, "small.mp4")
            Probe.record("tv_launch", "connected name=${c.name} network=${AppGraph.network.chosen.value?.kind}")
            assertTrue(LaunchHelpers.hasTag(compose, "tv-root"))
            assertFalse(LaunchHelpers.hasTag(compose, "phone-root"))
            assertFalse(LaunchHelpers.hasTag(compose, "welcome"))
            assertTrue(compose.onAllNodesWithText("Send", substring = true).fetchSemanticsNodes().isEmpty())
            compose.waitForIdle()
            Shots.take("tv-home")
        }
    }
}
