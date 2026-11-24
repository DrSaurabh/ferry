package app.ferry

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import app.ferry.media.PickerMode
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import app.ferry.ui.nav.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phone suite, Ph13 (B1, H59): leaving the picker with picks asks "Discard N picks?" — Keep picking keeps every pick
 * (system Back), Discard (top Back) leaves with `back result=discarded_picks` and the next visit starts empty. A
 * Back without picks leaves at once. Screenshot of the dialog for the wording review.
 */
@RunWith(AndroidJUnit4::class)
class PickerDiscardTest {
    private val ctx get() = Probe.context
    private val d get() = SendHelpers.device

    @Test
    fun leavingWithPicksAsksFirst() {
        LaunchHelpers.cleanPrefs(welcomeDone = true)
        LaunchHelpers.launch().use { sc ->
            val picker = Screen.Picker(PickerMode.Send)
            PhoneNav.waitScreen(sc, Screen.Home)
            SendHelpers.tap(ctx.getString(R.string.btn_send_photos))
            PhoneNav.waitScreen(sc, picker)
            SendHelpers.clickDesc("PICK_A2.jpg")
            SendHelpers.clickDesc("PICK_A3.jpg")
            PhoneNav.waitSelection(sc, "PICK_A2.jpg", "PICK_A3.jpg")

            d.pressBack()
            SendHelpers.waitText(PhoneNav.discardText(2))
            SendHelpers.settle()
            Shots.take("tour-discard-picks")
            SendHelpers.waitUntil("dialog logged", 5_000) { SendHelpers.logged("dialog name=discard_picks shown=1") }
            SendHelpers.tap(ctx.getString(R.string.keep_picking))
            SendHelpers.waitUntil("Keep picking closes the dialog", 5_000) { !d.hasObject(By.text(PhoneNav.discardText(2))) }
            assertEquals(picker, PhoneNav.screen(sc))
            PhoneNav.waitSelection(sc, "PICK_A2.jpg", "PICK_A3.jpg")

            PhoneNav.tapTopBack { d.hasObject(By.text(PhoneNav.discardText(2))) }
            SendHelpers.waitText(PhoneNav.discardText(2))
            SendHelpers.tap(ctx.getString(R.string.discard))
            PhoneNav.waitScreen(sc, Screen.Home, "Discard → Home")
            SendHelpers.waitUntil("discarded_picks logged", 5_000) { SendHelpers.logged("back screen=picker_send result=discarded_picks") }

            // Next visit starts empty; Back without picks leaves at once (no dialog).
            SendHelpers.tap(ctx.getString(R.string.btn_send_photos))
            PhoneNav.waitScreen(sc, picker)
            assertTrue("next visit starts empty", PhoneNav.picker(sc).selection.value.isEmpty())
            d.pressBack()
            PhoneNav.waitScreen(sc, Screen.Home, "Back without picks → Home")
            Probe.record("picker_discard", "ok")
        }
    }
}
