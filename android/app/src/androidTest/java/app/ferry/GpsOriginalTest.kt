package app.ferry

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ferry.core.upload.ItemStatus
import app.ferry.core.upload.Phase
import app.ferry.media.MediaPick
import app.ferry.media.SendCoordinator
import app.ferry.media.SendRequest
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phone suite class 2 (plan §4.14) [B1] [P2] [P5]: after the host granted ACCESS_MEDIA_LOCATION, the same photo
 * through the real service → PC-A is "Saved" from the original (host: `verify_inbox.py identical` = sha256 of the
 * fixture, GPS present, date within 2 s). Resend → "Already on PC"; **Send anyway** (tapped on the Transfer
 * screen) → the PC answers `skipped` again (identical content; host checks the PC log).
 */
@RunWith(AndroidJUnit4::class)
class GpsOriginalTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun prepare(id: Long) =
        runBlocking { SendCoordinator.prepare(Probe.context, SendRequest.Picks(listOf(MediaPick(id, false)))) }

    @Test
    fun originalSavedThenAlreadyOnPcThenSendAnyway() {
        assertTrue("ACCESS_MEDIA_LOCATION must be granted by the host", SendHelpers.hasLocationPermission())
        LaunchHelpers.cleanPrefs(welcomeDone = true) // manual = PC-A
        val row = SendHelpers.findImage(SendHelpers.gpsName)
        Probe.record("original_date_modified_s", row.dateModifiedS)
        LaunchHelpers.launch(open = MainActivity.OPEN_TRANSFER).use {
            SendHelpers.waitConnected(compose)
            val p = prepare(row.id)
            assertTrue("no location choice with the permission", p.needsLocationChoice.isEmpty())
            assertTrue("opened as the original", p.items[0].requireOriginal)
            assertEquals(row.dateModifiedS * 1000, p.items[0].mtimeMs)
            SendCoordinator.start(Probe.context, p)
            SendHelpers.waitTransfer(compose, "saved") { it?.phase == Phase.Finished }
            var s = AppGraph.transfers.state.value!!
            assertEquals(ItemStatus.Saved(SendHelpers.gpsName, false), s.items[0].status)
            assertEquals(0, s.summary!!.savedWithoutLocation)
            LaunchHelpers.waitForText(compose, "1 saved · 0 already on PC")
            LaunchHelpers.settle(compose)
            Shots.take("transfer-summary-saved")

            // Resend: a fresh run, answered by the pre-check
            val runs = SendHelpers.finishedRuns()
            SendCoordinator.start(Probe.context, prepare(row.id))
            SendHelpers.waitTransfer(compose, "already on PC") { SendHelpers.finishedRuns() > runs && it?.phase == Phase.Finished }
            s = AppGraph.transfers.state.value!!
            assertTrue("resend: ${s.items[0].status}", s.items[0].status is ItemStatus.AlreadyOnPc)
            assertEquals(1, s.summary!!.alreadyOnPc)
            LaunchHelpers.waitForText(compose, "0 saved · 1 already on PC")
            LaunchHelpers.settle(compose)
            Shots.take("transfer-summary-already")

            // Send anyway: uploaded without the pre-check, the PC compares content → skipped (identical)
            val runs2 = SendHelpers.finishedRuns()
            SendHelpers.tap("Send anyway")
            SendHelpers.waitTransfer(compose, "send anyway done") { SendHelpers.finishedRuns() > runs2 && it?.phase == Phase.Finished }
            s = AppGraph.transfers.state.value!!
            assertTrue("send anyway: ${s.items[0].status}", s.items[0].status is ItemStatus.AlreadyOnPc)
            Probe.record("original_send_anyway", s.items[0].status)
        }
    }
}
