package app.ferry

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ferry.core.HostPort
import app.ferry.core.upload.ItemStatus
import app.ferry.core.upload.Phase
import app.ferry.media.MediaPick
import app.ferry.media.SendCoordinator
import app.ferry.media.SendRequest
import app.ferry.net.ConnectionState
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phone suite class 6 (plan §4.14) [P6]: cancel a 300 MB upload at about 30 % on the Transfer screen → Cancelled
 * (host: no `.ferry-*.part` left in PC-A's inbox). Then the PC address is an unused port and nothing else is known
 * → a new send shows "Waiting for …" + Cancel within 15 s; Cancel → Cancelled. (Resuming when the PC comes back is
 * proven against a real ferry.py in `:core` `pcGoesAwayAndComesBack`.)
 */
@RunWith(AndroidJUnit4::class)
class CancelAndWaitingTest {
    private fun send(id: Long) {
        val p = runBlocking { SendCoordinator.prepare(Probe.context, SendRequest.Picks(listOf(MediaPick(id, false)))) }
        assertTrue(p.needsLocationChoice.isEmpty())
        SendCoordinator.start(Probe.context, p)
    }

    private fun cancelOnScreen() {
        SendHelpers.tap("Cancel")
        SendHelpers.tap("Stop sending")
    }

    @Test
    fun cancelMidUploadThenWaitingForPc() {
        LaunchHelpers.cleanPrefs(welcomeDone = true) // manual = PC-A
        val big = SendHelpers.findImage(Probe.arg("bigName", "BIG_300MB.jpg"))
        val small = SendHelpers.findImage(SendHelpers.gpsName)
        LaunchHelpers.launch(open = MainActivity.OPEN_TRANSFER).use {
            SendHelpers.waitConnected()
            send(big.id)
            SendHelpers.waitTransfer("big file at 25 %", 120_000) { s ->
                val st = s?.items?.firstOrNull()?.status
                st is ItemStatus.Sending && st.sent * 4 >= st.total
            }
            SendHelpers.settle()
            Shots.take("transfer-sending")
            cancelOnScreen()
            SendHelpers.waitTransfer("cancelled") { it?.phase == Phase.Finished }
            val s = AppGraph.transfers.state.value!!
            assertEquals("cancelled, not saved: ${s.items[0].status}", ItemStatus.Cancelled, s.items[0].status)
            assertEquals(1, s.summary!!.cancelled)
            SendHelpers.settle()
            Shots.take("transfer-cancelled")

            // No PC: unused port, nothing remembered (broadcast can't leave the emulator NAT [B27])
            AppGraph.prefs.lastKnown = null
            AppGraph.prefs.chosenName = null
            AppGraph.prefs.manual = HostPort.parse("10.0.2.2:18799")
            runBlocking { AppGraph.connection.relocate() }
            assertTrue(AppGraph.connection.state.value !is ConnectionState.Connected)
            send(small.id)
            SendHelpers.waitTransfer("waiting for PC", 15_000) { it?.phase == Phase.WaitingForPc }
            SendHelpers.waitText("continues automatically", 10_000)
            SendHelpers.settle()
            Shots.take("transfer-waiting")
            cancelOnScreen()
            SendHelpers.waitTransfer("waiting cancelled", 20_000) { it?.phase == Phase.Finished }
            assertEquals(ItemStatus.Cancelled, AppGraph.transfers.state.value!!.items[0].status)
        }
    }
}
