package app.ferry

import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import app.ferry.media.PickerMode
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import app.ferry.ui.nav.Screen
import app.ferry.ui.nav.logName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phone suite, Ph13 (B1, U1, L4; design §5.1, H69, CP138): the top Back and the system Back do the same thing on every
 * phone screen. Visits Settings, Transfer and From PC (each left once with the top Back and once with the system
 * Back → Home), the picker (album → album list with both Backs, the Albums tab inside an album → list, picks kept),
 * checks ⋮ on each screen and writes one "Something's wrong" marker from the picker; leaving the picker with picks
 * asks first; Back on Home leaves Ferry. Ph14 adds Preview, Ph18 Controller/Browse. Needs the PICK_ photos
 * (`push_pick_photos`) and media access (granted by the earlier classes). Checks are on app state (B57).
 */
@RunWith(AndroidJUnit4::class)
class BackEverywhereTest {
    private val ctx get() = Probe.context
    private val d get() = SendHelpers.device

    @Test
    fun backIsTheSameEverywhere() {
        LaunchHelpers.cleanPrefs(welcomeDone = true)
        LaunchHelpers.launch().use { sc ->
            SendHelpers.waitConnected()
            PhoneNav.waitScreen(sc, Screen.Home)
            PhoneNav.waitOverflow("home")
            assertFalse("Home has no Back (Back there leaves Ferry)", d.hasObject(By.text(PhoneNav.topBack)))

            // ---- Settings, Transfer, From PC: top Back and system Back both land on Home.
            for (s in listOf(Screen.Settings, Screen.Transfer, Screen.FromPc)) {
                for (how in listOf("top", "system")) {
                    sc.onActivity { it.nav!!.push(s) }
                    PhoneNav.waitScreen(sc, s)
                    PhoneNav.waitOverflow(s.logName)
                    if (how == "top") PhoneNav.tapTopBack() else d.pressBack()
                    PhoneNav.waitScreen(sc, Screen.Home, "$how Back from ${s.logName} → Home")
                    Probe.record("back_ok", "$how ${s.logName} → home")
                }
            }

            // ---- The picker: album → album list (both Backs), Albums tab in an album → list; picks survive.
            val picker = Screen.Picker(PickerMode.Send)
            SendHelpers.tap(ctx.getString(R.string.btn_send_photos))
            PhoneNav.waitScreen(sc, picker)
            PhoneNav.waitOverflow("picker")
            val vm = PhoneNav.picker(sc)
            SendHelpers.clickDesc("PICK_A1.jpg")
            PhoneNav.waitSelection(sc, "PICK_A1.jpg")
            SendHelpers.tap(ctx.getString(R.string.picker_albums))
            SendHelpers.tap("AlbumB", 15_000)
            SendHelpers.waitUntil("AlbumB open", 10_000) { vm.openAlbum.value != null }
            SendHelpers.clickDesc("PICK_B1.jpg")
            PhoneNav.waitSelection(sc, "PICK_A1.jpg", "PICK_B1.jpg")

            PhoneNav.tapTopBack() // album → album list
            SendHelpers.waitUntil("top Back: album list", 10_000) { vm.openAlbum.value == null && vm.albumsTab.value }
            assertEquals(picker, PhoneNav.screen(sc))
            PhoneNav.waitSelection(sc, "PICK_A1.jpg", "PICK_B1.jpg")

            SendHelpers.tap("AlbumA", 15_000)
            SendHelpers.waitUntil("AlbumA open", 10_000) { vm.openAlbum.value != null }
            SendHelpers.tap(ctx.getString(R.string.picker_albums)) // Albums tab inside an album → the list
            SendHelpers.waitUntil("Albums tab: album list", 10_000) { vm.openAlbum.value == null && vm.albumsTab.value }

            SendHelpers.tap("AlbumB", 15_000)
            SendHelpers.waitUntil("AlbumB open again", 10_000) { vm.openAlbum.value != null }
            d.pressBack() // system Back: album → album list, as the top Back
            SendHelpers.waitUntil("system Back: album list", 10_000) { vm.openAlbum.value == null && vm.albumsTab.value }
            assertEquals(picker, PhoneNav.screen(sc))
            PhoneNav.waitSelection(sc, "PICK_A1.jpg", "PICK_B1.jpg")

            // ---- ⋮ → Something's wrong in the picker: one marker with the picker's screen name (L4, PO5).
            d.wait(Until.findObject(By.desc(PhoneNav.more)), 10_000)!!.click()
            SendHelpers.tap(ctx.getString(R.string.wrong_menu))
            SendHelpers.waitText(ctx.getString(R.string.wrong_title))
            SendHelpers.tap(ctx.getString(R.string.wrong_skip))
            SendHelpers.waitUntil("wrong marker from the picker", 5_000) { SendHelpers.logged(" wrong screen=picker_send") }

            // ---- Leaving with picks asks; Keep picking keeps them; Discard goes Home.
            d.pressBack()
            SendHelpers.waitText(PhoneNav.discardText(2))
            SendHelpers.tap(ctx.getString(R.string.keep_picking))
            SendHelpers.waitUntil("dialog closed", 5_000) { !d.hasObject(By.text(PhoneNav.discardText(2))) }
            assertEquals(picker, PhoneNav.screen(sc))
            PhoneNav.waitSelection(sc, "PICK_A1.jpg", "PICK_B1.jpg")
            PhoneNav.tapTopBack()
            SendHelpers.waitText(PhoneNav.discardText(2))
            SendHelpers.tap(ctx.getString(R.string.discard))
            PhoneNav.waitScreen(sc, Screen.Home, "Discard → Home")
            SendHelpers.waitUntil("back result=discarded_picks", 5_000) { SendHelpers.logged("back screen=picker_send result=discarded_picks") }
            assertTrue("picks discarded", vm.selection.value.isEmpty())

            // ---- Back on Home leaves Ferry.
            d.pressBack()
            SendHelpers.waitUntil("Ferry left after Back on Home", 10_000) { sc.state != Lifecycle.State.RESUMED }
            Probe.record("back_on_home", sc.state)
        }
    }
}
