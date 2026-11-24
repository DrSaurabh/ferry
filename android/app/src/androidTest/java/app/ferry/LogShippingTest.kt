package app.ferry

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ferry.core.log.EventLog
import app.ferry.core.upload.Phase
import app.ferry.media.MediaPick
import app.ferry.media.SendCoordinator
import app.ferry.media.SendRequest
import app.ferry.probe.Probe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ph11b (L3, design §7.2, CP137): one photo sent to PC-A (`PICK_A1.jpg`, left by `PickerAcrossAlbumsTest`; already on
 * the PC, so it is skipped — still a whole send) → the log is shipped after the send ends: within 15 s the ship cursor
 * passes the `send.done` line. The host check `check_device_log A send.start,send.done` then finds it in PC-A's
 * `logs/<name> (<id>)/app-log.txt`.
 */
@RunWith(AndroidJUnit4::class)
class LogShippingTest {
    @Test
    fun aFinishedSendShipsTheLogToThePc() {
        LaunchHelpers.cleanPrefs(welcomeDone = true) // manual = PC-A
        val row = SendHelpers.findImage("PICK_A1.jpg")
        LaunchHelpers.launch(open = MainActivity.OPEN_TRANSFER).use {
            SendHelpers.waitConnected()
            val p = runBlocking { SendCoordinator.prepare(Probe.context, SendRequest.Picks(listOf(MediaPick(row.id, false)))) }
            SendCoordinator.start(Probe.context, if (p.needsLocationChoice.isEmpty()) p else SendCoordinator.withoutLocation(p))
            SendHelpers.waitTransfer("send finished") { it?.phase == Phase.Finished }
            var done: String? = null
            SendHelpers.waitUntil("send.done logged", 10_000) {
                done = AppGraph.events.snapshot().lastOrNull { it.contains(" send.done ") }
                done != null
            }
            Probe.record("send_done_line", done)
            val seq = EventLog.seqOf(done!!)!!
            SendHelpers.waitUntil("log shipped past send.done (#$seq)", 15_000) { AppGraph.events.shipped >= seq }
            Probe.record("shipped_seq", AppGraph.events.shipped)
            assertTrue(AppGraph.events.snapshot().any { it.contains(" send.start ") })
        }
    }
}
