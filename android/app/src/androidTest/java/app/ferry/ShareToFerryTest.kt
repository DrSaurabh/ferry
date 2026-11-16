package app.ferry

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ferry.core.upload.ItemStatus
import app.ferry.core.upload.Phase
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileNotFoundException

/**
 * Phone suite class 4 (plan §4.14) [P1b] [A5] [B19] [PB6]: Share → Ferry from **another app** whose files Ferry can
 * read only through the grant of the share. The sender is `app.ferry.sharehelper` [CP21]: three files in its
 * private folder behind a non-exported FileProvider, shared with `ACTION_SEND_MULTIPLE` + ClipData +
 * `FLAG_GRANT_READ_URI_PERMISSION`.
 *
 * - **Control** (the test can fail): before the share, Ferry's own ContentResolver can't open the helper's URI.
 * - Ferry's share activity reaches DESTROYED and the helper's activity is gone **before** the engine finishes.
 * - **B45 (Ph5a)**: after the share activity is destroyed and while the service runs, Ferry can still open the
 *   shared URI — the read grant rides on the service's start intent and outlives the activity. After the service
 *   has stopped, the same open is tried again and its result recorded (who held the grant).
 * - `UiDevice.sleep()` during the 30 MB item; all three rows Saved; host check: byte-identical copies on PC-A.
 * No Compose test rule (B50): waits poll the app state and UiAutomator.
 */
@RunWith(AndroidJUnit4::class)
class ShareToFerryTest {
    private val helper = "app.ferry.sharehelper"
    private val names = listOf("ferry-share-big.bin", "ferry-share-notes.txt", "ferry-share-small.bin")
    private fun uri(name: String) = Uri.parse("content://$helper.files/share/$name")

    /** Ferry opening the helper's URI itself: null = it worked, else the exception. */
    private fun tryOpen(u: Uri): Throwable? = try {
        Probe.context.contentResolver.openFileDescriptor(u, "r")!!.use { it.statSize }
        null
    } catch (e: Throwable) {
        e
    }

    /** The system's URI grants that mention the helper (who holds Ferry's read grant), into the job log. */
    private fun grants(stage: String) {
        val lines = SendHelpers.shell("dumpsys activity permissions").lines()
        val hits = lines.indices.filter { lines[it].contains(helper) || lines[it].contains("owner", ignoreCase = true) }
        Probe.record("grants_$stage", hits.flatMap { listOf(lines[it]) }.joinToString(" | ") { it.trim() }.take(3000).ifEmpty { "none" })
    }

    @After
    fun screenOn() = SendHelpers.wakeAndUnlock()

    @Test
    fun shareFromAnotherAppReadsThroughTheForwardedGrant() {
        LaunchHelpers.cleanPrefs(welcomeDone = true) // manual = PC-A
        // The helper writes its files without sharing (its first launch), so the control below is about access,
        // not about a missing file.
        Probe.record("helper_prepare", SendHelpers.shell("am start -W -n $helper/.ShareActivity --ez prepareOnly true").trim())
        SendHelpers.waitUntil("helper files written", 30_000) {
            SendHelpers.shell("run-as $helper ls -l files/share").contains(names[0])
        }
        grants("before_share")
        Probe.record("helper_files", SendHelpers.shell("run-as $helper ls -l files/share").replace('\n', ';'))

        // Control: without the share's grant Ferry can't read the helper's file.
        val denied = tryOpen(uri(names[2]))
        Probe.record("control_open_without_grant", denied)
        when (denied) {
            null -> fail("control: Ferry could open the helper's URI without a grant, so this test could not fail")
            is SecurityException -> {}
            is FileNotFoundException -> assertTrue("control: the file must exist, only access is missing: $denied",
                denied.message.orEmpty().contains("No content provider"))
            else -> fail("control: unexpected $denied")
        }
        Probe.record("control", "Ferry cannot open the helper's URI without the grant: ${denied?.javaClass?.simpleName}")

        // The share (the helper finishes right after starting Ferry's share activity).
        val runsBefore = SendHelpers.finishedRuns()
        Probe.record("helper_share", SendHelpers.shell("am start -n $helper/.ShareActivity").trim())
        SendHelpers.waitTransfer("3 shared files queued", 30_000) { it != null && it.items.size == 3 }
        SendHelpers.waitUntil("share activity destroyed", 15_000) { SendHelpers.logged("share activity destroyed") }
        SendHelpers.waitUntil("helper activity gone", 15_000) {
            SendHelpers.shell("dumpsys activity activities").lines().none { it.contains("ActivityRecord{") && it.contains(helper) }
        }
        val atDestroy = AppGraph.transfers.state.value!!
        Probe.record("state_when_share_activity_gone", "${atDestroy.phase} ${atDestroy.items.map { it.status }}")
        assertNotEquals("the engine finished before the share activity was gone; the grant was not tested", Phase.Finished, atDestroy.phase)
        assertEquals(names, atDestroy.items.map { it.name })

        // B45: the grant outlives the share activity (held by the service's start).
        val afterActivity = tryOpen(uri(names[2]))
        Probe.record("open_after_share_activity_destroyed", afterActivity ?: "ok")
        grants("after_share_activity_destroyed")
        assertEquals("Ferry lost the read grant when the share activity finished (B45)", null, afterActivity)

        // Screen off during the 30 MB item.
        SendHelpers.waitTransfer("30 MB item sending", 60_000) { s ->
            s?.items?.firstOrNull()?.status.let { it is ItemStatus.Sending || it is ItemStatus.Saved } || s?.phase == Phase.Finished
        }
        Probe.record("big_status_before_sleep", AppGraph.transfers.state.value?.items?.firstOrNull()?.status)
        SendHelpers.device.sleep()
        Thread.sleep(1_000)
        Probe.record("screen_on_during_upload", SendHelpers.device.isScreenOn)
        SendHelpers.waitTransfer("share finished", 180_000) { it?.phase == Phase.Finished && SendHelpers.finishedRuns() > runsBefore }
        SendHelpers.wakeAndUnlock()
        val s = AppGraph.transfers.state.value!!
        for (row in s.items) assertTrue("${row.name}: ${row.status}", row.status is ItemStatus.Saved)
        assertEquals(3, s.summary!!.saved)

        // Who held the grant: after the service stopped it should be gone again (recorded, not asserted).
        SendHelpers.waitUntil("service stopped", 30_000) { SendHelpers.logged("service stopped:") }
        Thread.sleep(1_000)
        Probe.record("open_after_service_stopped", tryOpen(uri(names[2])) ?: "still readable")
        grants("after_service_stopped")

        LaunchHelpers.launch(open = MainActivity.OPEN_TRANSFER).use {
            SendHelpers.waitText("3 saved", 15_000)
            SendHelpers.settle()
            Shots.take("share-summary")
        }
    }
}
