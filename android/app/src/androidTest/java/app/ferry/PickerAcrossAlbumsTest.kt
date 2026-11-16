package app.ferry

import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ferry.core.upload.ItemStatus
import app.ferry.core.upload.Phase
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import app.ferry.ui.common.MediaAccess
import app.ferry.ui.common.mediaAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phone suite class 3 (plan §4.14) [P3] [A17]: six photos made by the shell in `DCIM/AlbumA` (PICK_A1–3) and
 * `Pictures/AlbumB` (PICK_B1–3). In the real picker: select 2 in Recent, open Albums → AlbumB → select 1, Back,
 * Recent again → still selected, "3 selected"; Send → the Transfer screen, all 3 saved on PC-A in pick order
 * (host check: exactly these 3 PICK_ names in the inbox). UiAutomator only (no Compose test rule, B50).
 */
@RunWith(AndroidJUnit4::class)
class PickerAcrossAlbumsTest {
    private val picked = listOf("PICK_A1.jpg", "PICK_A2.jpg", "PICK_B1.jpg")

    @Test
    fun selectionSurvivesAlbumAndTabSwitches() {
        LaunchHelpers.cleanPrefs(welcomeDone = true) // manual = PC-A
        assertEquals(MediaAccess.Full, mediaAccess(Probe.context))
        for (n in listOf("PICK_A1.jpg", "PICK_A2.jpg", "PICK_A3.jpg", "PICK_B1.jpg", "PICK_B2.jpg", "PICK_B3.jpg")) {
            SendHelpers.findImage(n)
        }
        recordBuckets()
        LaunchHelpers.launch().use {
            SendHelpers.waitConnected()
            SendHelpers.tap("Send photos & videos")
            SendHelpers.findDesc("PICK_A1.jpg").click()
            SendHelpers.findDesc("PICK_A2.jpg").click()
            SendHelpers.waitText("2 selected")
            SendHelpers.settle()
            Shots.take("picker-recent")

            SendHelpers.tap("Albums")
            SendHelpers.waitText("AlbumB")
            SendHelpers.settle()
            Shots.take("picker-albums")
            SendHelpers.tap("AlbumB")
            SendHelpers.findDesc("PICK_B1.jpg").click()
            SendHelpers.waitText("3 selected")
            SendHelpers.waitText("Select all")
            SendHelpers.settle()
            Shots.take("picker-album-grid")

            SendHelpers.device.pressBack() // album grid → album list
            SendHelpers.waitText("AlbumA")
            SendHelpers.tap("Recent")
            val a1 = SendHelpers.findDesc("PICK_A1.jpg")
            val a2 = SendHelpers.findDesc("PICK_A2.jpg")
            val a3 = SendHelpers.findDesc("PICK_A3.jpg")
            Probe.record("picker_checked", "A1=${a1.isChecked} A2=${a2.isChecked} A3=${a3.isChecked}")
            assertTrue("A1 still selected after the album switch", a1.isChecked)
            assertTrue("A2 still selected after the album switch", a2.isChecked)
            assertTrue("A3 not selected", !a3.isChecked)
            SendHelpers.waitText("3 selected")
            SendHelpers.settle()
            Shots.take("picker-bottom-bar")

            SendHelpers.tap("Send")
            SendHelpers.waitTransfer("picked files saved", 90_000) { it?.phase == Phase.Finished && it.items.size == 3 }
            val s = AppGraph.transfers.state.value!!
            Probe.record("picker_sent", s.items.map { "${it.name}=${it.status}" })
            assertEquals("sent in pick order", picked, s.items.map { it.name })
            assertTrue(s.items.all { it.status is ItemStatus.Saved })
            SendHelpers.waitText("3 saved · 0 already on PC")
            SendHelpers.settle()
            Shots.take("picker-sent-summary")
        }
    }

    /** Which albums MediaStore put the six photos in (evidence for the job log). */
    private fun recordBuckets() {
        Probe.context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, "bucket_display_name", MediaStore.MediaColumns.DATE_MODIFIED),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'PICK_%'", null, null,
        )?.use { c -> while (c.moveToNext()) Probe.record("pick_row", "${c.getString(0)} album=${c.getString(1)} date=${c.getLong(2)}") }
    }
}
