package app.ferry

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.ferry.core.upload.ItemStatus
import app.ferry.core.upload.Phase
import app.ferry.media.MediaPick
import app.ferry.media.SendCoordinator
import app.ferry.media.SendRequest
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phone suite class 5 (plan §4.14) [P4] [PB13] [PB20]: a ~280 MB photo (made by the shell, B51) is sent; after 5 s
 * the notification shade shows the progress notification with "MB/s"; then **Home** (the activity no longer keeps
 * the process important) and `UiDevice.sleep()`. While the screen is off, `dumpsys activity services app.ferry`
 * must show `TransferService` running in the foreground with the `dataSync` type, so the upload is proven to run in
 * the foreground service and not in some UI scope. Wait ≤ 3 min; wake; summary "1 saved"; host check: the PC-A copy
 * has the phone file's size. No Compose test rule (B50).
 */
@RunWith(AndroidJUnit4::class)
class ScreenOffUploadTest {
    @After
    fun screenOn() = SendHelpers.wakeAndUnlock()

    /** The TransferService block of `dumpsys activity services app.ferry`. */
    private fun serviceBlock(dump: String): String? {
        val lines = dump.lines()
        val start = lines.indexOfFirst { it.contains("ServiceRecord{") && it.contains("TransferService") }
        if (start < 0) return null
        val end = (start + 1 until lines.size).firstOrNull { lines[it].contains("ServiceRecord{") } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    /** Clicks the expand arrow of the notification row whose title starts with "Sending" (no-op if not found). */
    private fun expandFerryNotification() {
        val title = SendHelpers.device.findObject(By.textStartsWith("Sending"))
        if (title == null) {
            Probe.record("notification_expanded", "no Sending row")
            return
        }
        var p: UiObject2? = title.parent
        repeat(6) {
            // The arrow on the title's own row (higher parents also hold the other notifications' arrows).
            val b = p?.findObjects(By.res("android", "expand_button"))
                ?.firstOrNull { Math.abs(it.visibleCenter.y - title.visibleCenter.y) < 200 }
            if (b != null) {
                b.click()
                Probe.record("notification_expanded", true)
                return
            }
            p = p?.parent
        }
        Probe.record("notification_expanded", false)
    }

    @Test
    fun uploadRunsInTheForegroundServiceWithTheScreenOff() {
        LaunchHelpers.cleanPrefs(welcomeDone = true) // manual = PC-A
        val big = SendHelpers.findImage(Probe.arg("screenOffName", "SCREENOFF_280MB.jpg"))
        val d = SendHelpers.device
        LaunchHelpers.launch(open = MainActivity.OPEN_TRANSFER).use {
            SendHelpers.waitConnected()
            val p = runBlocking { SendCoordinator.prepare(Probe.context, SendRequest.Picks(listOf(MediaPick(big.id, false)))) }
            assertTrue("location access was granted before this class", p.needsLocationChoice.isEmpty())
            SendCoordinator.start(Probe.context, p)
            SendHelpers.waitTransfer("sending", 60_000) { it?.items?.firstOrNull()?.status is ItemStatus.Sending }
            Thread.sleep(5_000)

            // Progress notification with the speed, shade opened before the sleep [PB20].
            d.openNotification()
            // Ph11b CI: another notification (the emulator's "Set a screen lock") can sit on top, auto-expanded, and
            // leave Ferry's collapsed (title + bar only): expand Ferry's own row, then look for the speed as before.
            var lastExpand = 0L
            try {
                SendHelpers.waitUntil("notification with MB/s", 20_000) {
                    d.hasObject(By.textContains("MB/s")) || run {
                        val now = System.currentTimeMillis()
                        if (now - lastExpand > 2_000) {
                            lastExpand = now
                            expandFerryNotification()
                        }
                        false
                    }
                }
            } finally {
                Probe.record("shade_texts", d.findObjects(By.textContains(" ")).take(20).joinToString(" | ") { it.text ?: "" })
            }
            Probe.record("notification_text", d.findObject(By.textContains("MB/s"))?.text)
            SendHelpers.settle()
            Shots.take("notification-sending")

            d.pressHome()
            Thread.sleep(1_500)
            d.sleep()
            Thread.sleep(3_000)
            assertFalse("screen should be off", d.isScreenOn)

            // While the screen is off: the upload runs in a dataSync foreground service [PB13].
            val dump = SendHelpers.shell("dumpsys activity services app.ferry")
            val block = serviceBlock(dump)
            Probe.record("dumpsys_transfer_service", block?.replace('\n', '|') ?: dump.take(2000).replace('\n', '|'))
            val st = AppGraph.transfers.state.value
            Probe.record("state_during_dumpsys", "${st?.phase} ${st?.items?.map { it.status }}")
            assertTrue("TransferService not in dumpsys", block != null)
            assertTrue("TransferService not foreground: $block", block!!.contains("isForeground=true"))
            val typeOk = block.contains("dataSync") || Regex("""types=(0x)?0*1\b""").containsMatchIn(block)
            assertTrue("foreground type is not dataSync: $block", typeOk)
            assertTrue("upload already over at the dumpsys check", st?.phase == Phase.Sending)
            Probe.record("foreground_check", "TransferService isForeground=true, type dataSync, screen off, phase ${st?.phase}")

            SendHelpers.waitTransfer("finished with the screen off", 180_000) { it?.phase == Phase.Finished }
            Probe.record("screen_on_at_finish", d.isScreenOn)
            SendHelpers.wakeAndUnlock()
            val s = AppGraph.transfers.state.value!!
            assertTrue("${s.items[0].status}", s.items[0].status is ItemStatus.Saved)
            assertEquals(1, s.summary!!.saved)
        }
        LaunchHelpers.launch(open = MainActivity.OPEN_TRANSFER).use {
            SendHelpers.waitText("1 saved", 15_000)
            SendHelpers.settle()
            Shots.take("screenoff-summary")
        }
    }
}
