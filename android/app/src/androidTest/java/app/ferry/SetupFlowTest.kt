package app.ferry

import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import app.ferry.media.PickerMode
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import app.ferry.ui.common.MediaAccess
import app.ferry.ui.common.mediaAccess
import app.ferry.ui.nav.Screen
import app.ferry.ui.phone.SetupStep
import app.ferry.ui.phone.SetupViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Phone suite, Ph13 (U2, U9, M16, M23, H94; plan CP142): the first setup on a **fresh install** — runs last, after
 * the host step `fresh_install` (the APK reinstalled: no permissions, no battery exemption, no "install unknown
 * apps"). API 34: four steps + the name screen, no vivo step. Media refused at step 1 → Back on step 2 returns to
 * step 1 → Skip; notifications allowed in Android's dialog; the battery and "install unknown apps" system pages are
 * reached (asserted by the resumed activity in `dumpsys`) and left with Back; name screen → Done → Home; the refused
 * media permission shows the picker's banner with Fix (no pop-up). Checks are on app state; screenshots of every
 * step for the wording review.
 */
@RunWith(AndroidJUnit4::class)
class SetupFlowTest {
    private val ctx get() = Probe.context
    private val d get() = SendHelpers.device
    private val system = Pattern.compile(".*permissioncontroller.*")

    private fun step(): String? = AppGraph.prefs.firstSetupStep

    private fun waitStep(s: SetupStep) = SendHelpers.waitUntil("setup step ${s.logName}", 15_000) { step() == s.logName }

    private fun shot(name: String) {
        SendHelpers.settle()
        Shots.take("tour-setup-$name")
    }

    /** The activity Android shows in front now (`topResumedActivity`, else `mResumedActivity`). */
    private fun topActivity(): String {
        val out = SendHelpers.shell("dumpsys activity activities")
        return out.lineSequence().firstOrNull { it.contains("topResumedActivity") }?.trim()
            ?: out.lineSequence().firstOrNull { it.contains("mResumedActivity") }?.trim() ?: "?"
    }

    private fun waitSystemPage(what: String): String {
        var top = ""
        SendHelpers.waitUntil("$what page in front", 15_000) {
            top = topActivity()
            top.contains("com.android.settings") || top.contains("permissioncontroller")
        }
        Probe.record("setup_system_page", "$what: $top")
        return top
    }

    private fun steps(sc: ActivityScenario<MainActivity>): List<SetupStep> {
        var s: List<SetupStep> = emptyList()
        sc.onActivity { a -> s = ViewModelProvider(a)[SetupViewModel::class.java].steps }
        return s
    }

    @Test
    fun freshInstallSetup() {
        LaunchHelpers.cleanPrefs(welcomeDone = false)
        assertEquals("fresh install: no media access", MediaAccess.None, mediaAccess(ctx))
        LaunchHelpers.launch().use { sc ->
            waitStep(SetupStep.Media)
            val planned = steps(sc)
            Probe.record("setup_steps", planned.map { it.logName })
            assertEquals("API ${Build.VERSION.SDK_INT}: four steps and the name, no vivo step",
                listOf(SetupStep.Media, SetupStep.Notifications, SetupStep.Battery, SetupStep.Updates, SetupStep.Name), planned)
            shot("1-media")

            // Step 1: Android's dialog, refused.
            SendHelpers.tap(ctx.getString(R.string.setup_allow))
            assertTrue("media dialog shown", d.wait(Until.hasObject(By.pkg(system)), 15_000))
            shot("1-media-system")
            val deny = d.findObject(By.res(Pattern.compile(".*:id/permission_deny_button")))
            if (deny != null) deny.click() else d.pressBack()
            waitStep(SetupStep.Notifications)
            assertEquals(MediaAccess.None, mediaAccess(ctx))
            SendHelpers.waitUntil("media refusal logged", 5_000) { SendHelpers.logged("perm name=media result=denied") }
            shot("2-notifications")

            // Back on step 2 → step 1 (H94); Skip → step 2 again.
            d.pressBack()
            waitStep(SetupStep.Media)
            SendHelpers.tap(ctx.getString(R.string.setup_skip))
            waitStep(SetupStep.Notifications)

            // Step 2: notifications allowed in Android's dialog.
            SendHelpers.tap(ctx.getString(R.string.setup_allow))
            assertTrue("notification dialog shown", d.wait(Until.hasObject(By.pkg(system)), 15_000))
            val allow = d.wait(Until.findObject(By.res(Pattern.compile(".*:id/permission_allow_button"))), 5_000)
            checkNotNull(allow) { "no Allow button in the notification dialog" }.click()
            waitStep(SetupStep.Battery)
            SendHelpers.waitUntil("notifications granted logged", 5_000) { SendHelpers.logged("perm name=notifications result=granted") }
            shot("3-battery")

            // Step 3: the battery page is reached, then Back.
            SendHelpers.tap(ctx.getString(R.string.setup_allow))
            waitSystemPage("battery")
            shot("3-battery-system")
            d.pressBack()
            waitStep(SetupStep.Updates)
            SendHelpers.waitUntil("battery result logged", 5_000) { SendHelpers.logged("perm name=battery result=") }
            shot("4-updates")

            // Step 4: "Install unknown apps" for Ferry (`package:` URI, M16), then Back.
            SendHelpers.tap(ctx.getString(R.string.setup_open))
            val top = waitSystemPage("install unknown apps")
            assertTrue("a Settings page, not Ferry: $top", top.contains("com.android.settings"))
            shot("4-updates-system")
            d.pressBack()
            waitStep(SetupStep.Name)
            SendHelpers.waitUntil("updates result logged", 5_000) { SendHelpers.logged("perm name=updates result=") }
            shot("5-name")

            // Name screen → Done → Home; setup is finished and forgotten.
            SendHelpers.tap(ctx.getString(R.string.setup_done))
            PhoneNav.waitScreen(sc, Screen.Home, "Home after setup")
            assertTrue(AppGraph.prefs.welcomeDone)
            assertNull(AppGraph.prefs.firstSetupStep)

            // Media was refused: the picker shows the banner with Fix (U2), no permission pop-up.
            SendHelpers.tap(ctx.getString(R.string.btn_send_photos))
            PhoneNav.waitScreen(sc, Screen.Picker(PickerMode.Send))
            SendHelpers.waitText(ctx.getString(R.string.missing_media))
            assertTrue("no system dialog by itself", !d.hasObject(By.pkg(system)))
            shot("picker-banner")
            Probe.record("setup_flow", "ok")
        }
    }
}
