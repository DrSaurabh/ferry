package app.ferry

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * Phone suite class 0 (plan §4.14) [P8] [PB20]: manual address = PC-A → Home shows "Connected to …"; phone UI,
 * not TV. Also the Welcome and Settings screens, with screenshots.
 */
@RunWith(AndroidJUnit4::class)
class PhoneLaunchTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun homeShowsConnectedViaManualAddress() {
        LaunchHelpers.cleanPrefs(welcomeDone = true)
        assertFalse("phone emulator must not be in TV mode", DeviceMode.isTv(Probe.context, null))
        LaunchHelpers.launch().use {
            LaunchHelpers.waitForText(compose, "Connected to ")
            val c = AppGraph.connection.state.value as ConnectionState.Connected
            Probe.record("phone_launch", "connected name=${c.name} base=${c.api.base} network=${AppGraph.network.chosen.value?.kind}")
            assertTrue(LaunchHelpers.hasTag(compose, "phone-root"))
            assertFalse(LaunchHelpers.hasTag(compose, "tv-root"))
            compose.onNodeWithText("Send photos & videos").assertExists()
            Shots.take("home-connected")
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithTag("settings").assertExists()
            compose.waitForIdle()
            Shots.take("settings")
        }
    }

    @Test
    fun welcomeFirstThenHome() {
        LaunchHelpers.cleanPrefs(welcomeDone = false)
        LaunchHelpers.launch().use {
            compose.waitUntil(10_000) { LaunchHelpers.hasTag(compose, "welcome") }
            Shots.take("welcome")
            compose.onNodeWithText("Continue").performClick()
            compose.waitUntil(10_000) { LaunchHelpers.hasTag(compose, "home") }
            assertTrue(AppGraph.prefs.welcomeDone)
            assertTrue(compose.onAllNodesWithText("Welcome to Ferry").fetchSemanticsNodes().isEmpty())
        }
    }
}
