package app.ferry

import android.content.Intent
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import app.ferry.core.HostPort
import app.ferry.probe.Probe
import app.ferry.probe.Shots

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

    fun waitForText(rule: ComposeTestRule, text: String, timeoutMs: Long = 30_000) =
        waitFor("text '$text'", rule, timeoutMs) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    /**
     * waitUntil that leaves evidence when it times out (artifacts can't be downloaded here, B27): a screenshot,
     * the connection state and the app's RingLog go into the probe results / job log.
     */
    fun waitFor(what: String, rule: ComposeTestRule, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMs, condition)
        } catch (e: Throwable) {
            Probe.record("wait_failed", "$what; state=${AppGraph.connection.state.value} network=${AppGraph.network.chosen.value}")
            for (line in AppGraph.ringLog.snapshot().takeLast(40)) Probe.record("ringlog", line)
            runCatching { Shots.take("fail-" + what.filter { it.isLetterOrDigit() }.take(30)) }
            throw e
        }
    }

    /** Lets the last frame reach the screen before a screenshot. */
    fun settle(rule: ComposeTestRule) {
        rule.waitForIdle()
        Thread.sleep(700)
    }

    fun hasTag(rule: ComposeTestRule, tag: String): Boolean =
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
}
