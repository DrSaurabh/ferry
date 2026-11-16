package app.ferry

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import app.ferry.net.ConnectionState
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * API 25 suite (plan §4.14) [A3] [H17]: MainActivity launches on the Fire OS 6 API level (phone UI on the default
 * image) and Home shows "Connected to …". The suite passes `app.ferry.debug.NET=any` [PR2] (the image's only
 * network is reported as cellular). Ph7 [CP17]: From PC lists `report.pdf` with "Downloading from the PC needs
 * Android 10 or newer." and no download action (a tap starts nothing). UiAutomator + app state, no Compose rule
 * (Ph7: the From PC screen refreshes from IO, B50).
 */
@RunWith(AndroidJUnit4::class)
class Api25LaunchTest {
    @Test
    fun launchesAndConnects() {
        LaunchHelpers.cleanPrefs(welcomeDone = true)
        LaunchHelpers.launch().use {
            SendHelpers.waitText("Connected to ")
            val c = AppGraph.connection.state.value as ConnectionState.Connected
            Probe.record("api25_launch", "sdk=${Build.VERSION.SDK_INT} connected name=${c.name} network=${AppGraph.network.chosen.value?.kind}")
            assertFalse("phone layout expected", AppGraph.isTv)
            SendHelpers.settle()
            Shots.take("home-connected")

            // From PC on API 25: the list, the "needs Android 10" line, no download [CP17]
            val btn = SendHelpers.device.wait(Until.findObject(By.textStartsWith("From PC")), 10_000)
            checkNotNull(btn) { "no From PC button" }.click()
            SendHelpers.waitText("report.pdf", 30_000)
            SendHelpers.waitText(Probe.context.getString(R.string.downloads_need_android10), 10_000)
            SendHelpers.tap("report.pdf")
            Thread.sleep(2_000)
            Probe.record("api25_downloads", AppGraph.downloads.state.value)
            assertTrue("a download started on API 25", AppGraph.downloads.state.value.isEmpty())
            assertFalse(SendHelpers.device.hasObject(By.text(Probe.context.getString(R.string.download_all))))
            SendHelpers.settle()
            Shots.take("from-pc-needs-android10")
        }
    }
}
