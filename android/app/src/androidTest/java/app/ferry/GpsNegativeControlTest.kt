package app.ferry

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ferry.core.HostPort
import app.ferry.core.upload.ItemStatus
import app.ferry.core.upload.Phase
import app.ferry.media.MediaPick
import app.ferry.media.SendCoordinator
import app.ferry.media.SendRequest
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phone suite class 1 (plan §4.14) [B1] [A2]: run **without** ACCESS_MEDIA_LOCATION. The shell-made `gps.jpg` needs
 * the location choice; "Send without location" → the real TransferService → PC-B; the row says "Saved without
 * location". The host then checks with `verify_inbox.py redacted` that PC-B's copy is not the GPS original (else the
 * GPS test would be inconclusive and the build fails).
 */
@RunWith(AndroidJUnit4::class)
class GpsNegativeControlTest {
    @Test
    fun sendWithoutLocationToPcB() {
        assertFalse("ACCESS_MEDIA_LOCATION must not be granted yet", SendHelpers.hasLocationPermission())
        LaunchHelpers.cleanPrefs(welcomeDone = true)
        AppGraph.prefs.manual = HostPort.parse(SendHelpers.pcB)
        val row = SendHelpers.findImage(SendHelpers.gpsName)
        Probe.record("negative_owner", row.owner)
        assertNotEquals("Ferry must not own the row (owners are never redacted)", "app.ferry", row.owner)
        LaunchHelpers.launch(open = MainActivity.OPEN_TRANSFER).use {
            SendHelpers.waitConnected()
            val p = runBlocking { SendCoordinator.prepare(Probe.context, SendRequest.Picks(listOf(MediaPick(row.id, false)))) }
            assertEquals(1, p.items.size)
            assertEquals("the original can't be read: location choice needed", listOf(p.items[0].id), p.needsLocationChoice)
            assertFalse(p.items[0].requireOriginal)
            val w = SendCoordinator.withoutLocation(p)
            assertFalse(w.items[0].original)
            assertTrue(w.needsLocationChoice.isEmpty())
            SendCoordinator.start(Probe.context, w)
            SendHelpers.waitTransfer("saved without location") { it?.phase == Phase.Finished }
            val s = AppGraph.transfers.state.value!!
            assertTrue("row saved: ${s.items[0].status}", s.items[0].status is ItemStatus.Saved)
            assertEquals(1, s.summary!!.savedWithoutLocation)
            SendHelpers.waitText("Saved without location")
            SendHelpers.settle()
            Shots.take("transfer-saved-without-location")
            Probe.record("negative_summary", s.summary)
        }
    }
}
