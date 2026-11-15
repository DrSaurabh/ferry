package app.ferry

import android.content.Intent
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import app.ferry.core.HostPort
import app.ferry.probe.Probe

/** Shared setup of the Ph4 launch tests (plan §4.14): clean Prefs, PC address from `-e pcA`, debug extras. */
object LaunchHelpers {
    fun cleanPrefs(welcomeDone: Boolean) {
        AppGraph.prefs.clear()
        AppGraph.prefs.manual = HostPort.parse(Probe.pc)
        AppGraph.prefs.welcomeDone = welcomeDone
    }

    /** MainActivity with the debug extras the suite asks for (`-e app.ferry.debug.NET any` on API 25 [PR2]). */
    fun launch(): ActivityScenario<MainActivity> {
        val i = Intent(Probe.context, MainActivity::class.java)
        Probe.arg(DeviceMode.EXTRA_NET, "").takeIf { it.isNotEmpty() }?.let { i.putExtra(DeviceMode.EXTRA_NET, it) }
        return ActivityScenario.launch(i)
    }

    fun waitForText(rule: ComposeTestRule, text: String, timeoutMs: Long = 30_000) {
        rule.waitUntil(timeoutMs) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    fun hasTag(rule: ComposeTestRule, tag: String): Boolean =
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
}
