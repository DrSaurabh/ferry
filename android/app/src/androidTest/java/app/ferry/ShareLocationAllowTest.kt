package app.ferry

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import app.ferry.core.HostPort
import app.ferry.core.upload.ItemStatus
import app.ferry.core.upload.Phase
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import app.ferry.share.ShareReceiverActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Phone suite, Stage 7 audit O1: **Allow** in the share activity's location choice. Runs after
 * `GpsNegativeControlTest`, still without ACCESS_MEDIA_LOCATION. A shell-made GPS photo is shared into the real
 * `ShareReceiverActivity` (translucent, `noHistory`); the dialog's Allow asks Android for the photo permissions
 * (`PermissionPlanner.locationRequest`), the result comes back to the activity, the send is prepared again and the
 * **original** goes to PC-U (the host then checks bytes, GPS and date with `verify_inbox.py identical`).
 * Taps go through UiAutomator; every check is on app state (permission, engine rows, RingLog) [B57].
 */
@RunWith(AndroidJUnit4::class)
class ShareLocationAllowTest {
    private val ctx get() = Probe.context
    private val name get() = Probe.arg("shareGpsName", "IMG_20250102_130000.jpg")
    private fun results() = AppGraph.ringLog.snapshot().count { it.contains("photo location permission:") }
    private fun destroyed() = AppGraph.ringLog.snapshot().count { it.contains("share activity destroyed") }

    @Test
    fun allowInTheShareDialogSendsTheOriginal() {
        assertFalse("ACCESS_MEDIA_LOCATION must not be granted yet", SendHelpers.hasLocationPermission())
        LaunchHelpers.cleanPrefs(welcomeDone = true)
        AppGraph.prefs.manual = HostPort.parse(Probe.arg("pcU", "10.0.2.2:18767"))
        val row = SendHelpers.findImage(name)
        val runs = SendHelpers.finishedRuns()
        val resultsBefore = results()
        val destroyedBefore = destroyed()

        ctx.startActivity(
            Intent(Intent.ACTION_SEND).setClass(ctx, ShareReceiverActivity::class.java).setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, row.uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        SendHelpers.waitText(ctx.getString(R.string.location_without), 30_000)
        SendHelpers.settle()
        Shots.take("share-location-dialog")
        assertEquals("nothing is queued before the choice", runs, SendHelpers.finishedRuns())
        SendHelpers.tap(ctx.getString(R.string.location_allow))

        // Android's own dialog, if it shows one (with photo access already granted it may grant without asking).
        val d = SendHelpers.device
        val allow = d.wait(Until.findObject(By.res(Pattern.compile(".*:id/permission_allow(_all)?_button"))), 8_000)
        Probe.record("o1_system_dialog", allow?.resourceName ?: "none")
        if (allow != null) {
            Shots.take("share-location-system-dialog")
            allow.click()
        }
        SendHelpers.waitUntil("permission result delivered to the share activity", 30_000) { results() > resultsBefore }
        assertTrue("photo location granted after Allow", SendHelpers.hasLocationPermission())

        SendHelpers.waitTransfer("original saved") { SendHelpers.finishedRuns() > runs && it?.phase == Phase.Finished }
        val s = AppGraph.transfers.state.value!!
        assertEquals(1, s.items.size)
        assertEquals(ItemStatus.Saved(name, false), s.items[0].status)
        assertEquals("sent as the original, not without location", 0, s.summary!!.savedWithoutLocation)
        SendHelpers.waitUntil("share activity finished", 15_000) { destroyed() > destroyedBefore }
        Probe.record("o1_summary", s.summary)
    }
}
