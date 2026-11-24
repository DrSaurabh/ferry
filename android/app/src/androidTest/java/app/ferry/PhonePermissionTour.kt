package app.ferry

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import app.ferry.ui.common.MediaAccess
import app.ferry.ui.common.mediaAccess
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Phone suite, Ph10 screenshot tour: the **real** first "Send photos & videos" on a fresh install with no media
 * permission (design §4.2 [A15] [H23]; adapted in Ph13: the picker opens with "Ferry isn't allowed…" + Fix, U2):
 * Fix → Ferry's explanation → Continue → Android's own permission dialog → "Don't
 * allow" once → Ferry explains again. Runs before the host grants any media permission; refuses only once, so
 * Android keeps asking and the later `pm grant`s work as before. Checks are on app state (access None, the RingLog
 * line of the permission result); the dialogs are photographed for the wording review.
 */
@RunWith(AndroidJUnit4::class)
class PhonePermissionTour {
    private val ctx get() = Probe.context
    private val system = Pattern.compile(".*permissioncontroller.*")

    @Test
    fun firstSendPhotosAsksForPermission() {
        assertEquals("no media permission yet", MediaAccess.None, mediaAccess(ctx))
        LaunchHelpers.cleanPrefs(welcomeDone = true)
        LaunchHelpers.launch().use {
            // No wait for the PC: this class is about the permission dialogs (run 1: the PC once didn't answer here).
            SendHelpers.tap(ctx.getString(R.string.btn_send_photos))
            // Ph13 (U2, H72): no pop-up by itself — the picker opens with the banner; its Fix starts the flow.
            SendHelpers.waitText(ctx.getString(R.string.missing_media))
            SendHelpers.settle()
            Shots.take("tour-perm-banner")
            SendHelpers.tap(ctx.getString(R.string.fix))
            SendHelpers.waitText(ctx.getString(R.string.continue_))
            SendHelpers.settle()
            Shots.take("tour-perm-rationale")
            SendHelpers.tap(ctx.getString(R.string.continue_))
            val d = SendHelpers.device
            val shown = d.wait(Until.hasObject(By.pkg(system)), 15_000)
            Probe.record("tour_system_dialog_shown", shown)
            SendHelpers.settle()
            Shots.take("tour-perm-system-dialog")
            val deny = d.findObject(By.res(Pattern.compile(".*:id/permission_deny_button")))
            if (deny != null) deny.click() else d.pressBack()
            Probe.record("tour_system_dialog_deny", if (deny != null) "button" else "back")
            SendHelpers.waitUntil("permission result", 15_000) { SendHelpers.logged("media permissions") }
            assertEquals("still no media access after Don't allow", MediaAccess.None, mediaAccess(ctx))
            SendHelpers.settle()
            Shots.take("tour-perm-rationale-again")
            SendHelpers.tap(ctx.getString(R.string.cancel))
        }
    }
}
