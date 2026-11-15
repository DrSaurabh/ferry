package app.ferry

import android.os.Build
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ferry.net.ConnectionState
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * API 25 suite (plan §4.14) [A3] [H17]: MainActivity launches on the Fire OS 6 API level (phone UI on the default
 * image) and Home shows "Connected to …". The suite passes `app.ferry.debug.NET=any` [PR2] (the image's only
 * network is reported as cellular). Ph7 adds the "needs Android 10" From PC line.
 */
@RunWith(AndroidJUnit4::class)
class Api25LaunchTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun launchesAndConnects() {
        LaunchHelpers.cleanPrefs(welcomeDone = true)
        LaunchHelpers.launch().use {
            LaunchHelpers.waitForText(compose, "Connected to ")
            val c = AppGraph.connection.state.value as ConnectionState.Connected
            Probe.record("api25_launch", "sdk=${Build.VERSION.SDK_INT} connected name=${c.name} network=${AppGraph.network.chosen.value?.kind}")
            assertTrue(LaunchHelpers.hasTag(compose, "phone-root"))
            LaunchHelpers.settle(compose)
            Shots.take("home-connected")
        }
    }
}
