package app.ferry

import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import app.ferry.core.upload.ItemStatus
import app.ferry.media.PickerMode
import app.ferry.ui.phone.PickerViewModel
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
 * `Pictures/AlbumB` (PICK_B1–3). In the real picker: select A1, A2 in Recent, open Albums → AlbumB → select B1, Back,
 * Recent again → "3 selected", and tapping A1 unselects it (so it was still selected), tapping again re-adds it; Send → the Transfer screen, all 3 saved on PC-A in pick order
 * (host check: exactly these 3 PICK_ names in the inbox). UiAutomator only (no Compose test rule, B50).
 */
@RunWith(AndroidJUnit4::class)
class PickerAcrossAlbumsTest {
    private val picked = listOf("PICK_A2.jpg", "PICK_B1.jpg", "PICK_A1.jpg") // pick order after the re-tap of A1

    @Test
    fun selectionSurvivesAlbumAndTabSwitches() {
        LaunchHelpers.cleanPrefs(welcomeDone = true) // manual = PC-A
        assertEquals(MediaAccess.Full, mediaAccess(Probe.context))
        for (n in listOf("PICK_A1.jpg", "PICK_A2.jpg", "PICK_A3.jpg", "PICK_B1.jpg", "PICK_B2.jpg", "PICK_B3.jpg")) {
            SendHelpers.findImage(n)
        }
        recordBuckets()
        LaunchHelpers.launch().use { sc ->
            SendHelpers.waitConnected()
            SendHelpers.tap("Send photos & videos")
            SendHelpers.findDesc("PICK_A1.jpg").click()
            SendHelpers.findDesc("PICK_A2.jpg").click()
            waitSelection(sc, "PICK_A1.jpg", "PICK_A2.jpg")
            SendHelpers.settle()
            Shots.take("picker-recent")

            SendHelpers.tap("Albums")
            SendHelpers.waitText("AlbumB")
            SendHelpers.settle()
            Shots.take("picker-albums")
            SendHelpers.tap("AlbumB")
            SendHelpers.findDesc("PICK_B1.jpg").click()
            waitSelection(sc, "PICK_A1.jpg", "PICK_A2.jpg", "PICK_B1.jpg")
            SendHelpers.waitText("Select all")
            SendHelpers.settle()
            Shots.take("picker-album-grid")

            SendHelpers.device.pressBack() // album grid → album list
            SendHelpers.waitText("AlbumA")
            SendHelpers.tap("Recent")
            SendHelpers.findDesc("PICK_A1.jpg")
            waitSelection(sc, "PICK_A1.jpg", "PICK_A2.jpg", "PICK_B1.jpg") // kept across the album and tab switch
            // Tapping A1 now unselects it, tapping again re-adds it at the end of the ordered selection (B55).
            SendHelpers.findDesc("PICK_A1.jpg").click()
            waitSelection(sc, "PICK_A2.jpg", "PICK_B1.jpg")
            SendHelpers.findDesc("PICK_A1.jpg").click()
            waitSelection(sc, "PICK_A2.jpg", "PICK_B1.jpg", "PICK_A1.jpg")
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

    /**
     * The picker's real selection (names, in pick order), read from its ViewModel in the activity's store (B57):
     * UiAutomator's view of the Compose text can lag (seen on main run 63: "3 selected" on screen, old text read).
     */
    private fun selection(sc: ActivityScenario<MainActivity>): List<String?> {
        var names: List<String?> = emptyList()
        sc.onActivity { a ->
            val vm = ViewModelProvider(a)["picker-${PickerMode.Send}", PickerViewModel::class.java]
            names = vm.selection.value.map { vm.library.value.byId[it]?.name }
        }
        return names
    }

    private fun waitSelection(sc: ActivityScenario<MainActivity>, vararg want: String) {
        try {
            SendHelpers.waitUntil("selection ${want.toList()}", 15_000) { selection(sc) == want.toList() }
        } finally {
            Probe.record("picker_selection", selection(sc))
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
