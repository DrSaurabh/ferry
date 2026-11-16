package app.ferry

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ferry.cast.CastPhase
import app.ferry.cast.CastProblem
import app.ferry.cast.CastSession
import app.ferry.core.HostPort
import app.ferry.core.model.CastRequest
import app.ferry.core.model.CastState
import app.ferry.net.ConnectionState
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import app.ferry.ui.nav.Screen
import app.ferry.ui.phone.CastViewModel
import app.ferry.ui.phone.TvChoice
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phone suite class 10 (plan §4.14), Ph10: screenshots of the screens and states the other classes don't reach, for
 * the wording review. Home: PC not found / not on Wi-Fi / older Ferry on the PC (shown with
 * `ConnectionManager.showForTest`) and the interrupted-transfer card; Settings with "Still stopping?" open; Play on TV
 * with no TV (real: broadcasts don't reach the host from the emulator) and with several TVs (list set in the
 * ViewModel); the Remote while starting and after each way a cast can end (`Casting.showForTest`, no cast running).
 * The real screens with real data (picker, transfer, summary, From PC, Remote playing/paused, notifications) are shot
 * by the classes that test them. Every check is on app state; the text on screen is only photographed (B57, B82).
 */
@RunWith(AndroidJUnit4::class)
class PhoneScreenshotTour {
    private val ctx get() = Probe.context

    private fun screen(sc: ActivityScenario<MainActivity>): Screen? {
        var s: Screen? = null
        sc.onActivity { s = it.nav?.current }
        return s
    }

    private fun go(sc: ActivityScenario<MainActivity>, s: Screen) {
        sc.onActivity { it.nav!!.replaceAll(Screen.Home); if (s != Screen.Home) it.nav!!.push(s) }
        SendHelpers.waitUntil("screen $s", 10_000) { screen(sc) == s }
    }

    private fun shot(name: String) {
        SendHelpers.settle()
        Shots.take("tour-$name")
    }

    @Test
    fun tour() {
        LaunchHelpers.cleanPrefs(welcomeDone = true)
        AppGraph.prefs.lastTv = null // Play on TV must not find a remembered TV here
        AppGraph.casting.showForTest(null)
        LaunchHelpers.launch().use { sc ->
            SendHelpers.waitConnected()
            SendHelpers.waitUntil("Home", 10_000) { screen(sc) == Screen.Home }
            shot("home-connected")

            // ---- Home: the three "not connected" states.
            val conn = AppGraph.connection
            val states = listOf(
                "home-not-found" to ConnectionState.NotFound(emptyList()),
                "home-no-wifi" to ConnectionState.NoWifi,
                "home-old-pc" to ConnectionState.OldPc(HostPort.parse(Probe.pc)!!),
            )
            for ((name, s) in states) {
                Probe.instrumentation.runOnMainSync { conn.showForTest(s) }
                SendHelpers.waitUntil(name, 5_000) { conn.state.value == s }
                shot(name)
            }
            conn.refresh()
            SendHelpers.waitConnected()

            // ---- Home after vivo stopped a transfer (interrupted card with the vivo steps) [A8].
            AppGraph.interrupted.value = 3
            shot("home-interrupted")
            AppGraph.interrupted.value = 0

            // ---- Settings with "Still stopping?" open.
            go(sc, Screen.Settings)
            SendHelpers.tap(ctx.getString(R.string.settings_still_stopping))
            shot("settings-still-stopping")

            // ---- Play on TV: no TV answers (real), then several TVs (list set in the ViewModel).
            var vm: CastViewModel? = null
            sc.onActivity { vm = ViewModelProvider(it)[CastViewModel::class.java] }
            val cast = checkNotNull(vm)
            val finds = cast.findsDone
            go(sc, Screen.PlayOnTv)
            SendHelpers.waitUntil("find finished", 30_000) { cast.findsDone > finds && !cast.finding.value }
            Probe.record("tour_tvs_found", cast.found.value)
            assertTrue("no TV on the emulator's network", cast.found.value.isEmpty() && cast.chosen.value == null)
            shot("play-on-tv-no-tv")
            Probe.instrumentation.runOnMainSync {
                cast.found.value = listOf(
                    TvChoice(HostPort("192.168.1.20", 8766), "Living room Fire TV"),
                    TvChoice(HostPort("192.168.1.21", 8766), "Bedroom Fire TV"),
                )
                cast.chosen.value = null
            }
            shot("play-on-tv-several-tvs")
            Probe.instrumentation.runOnMainSync { cast.found.value = emptyList() }

            // ---- The Remote: starting, then every way a cast ends.
            val tv = HostPort("192.168.1.20", 8766)
            val video = CastState(kind = "video", count = 1, title = "VID_20250101_120000.mp4", playing = true,
                positionMs = 83_000, durationMs = 245_000)
            val starting = CastSession(901, tv, "Living room Fire TV", CastRequest.VIDEO, 1, CastPhase.Starting)
            Probe.instrumentation.runOnMainSync { AppGraph.casting.showForTest(starting) }
            go(sc, Screen.Remote)
            shot("remote-starting")
            val endings = listOf<Pair<String, CastProblem?>>(
                "remote-ended-stop" to null,
                "remote-ended-cant-serve" to CastProblem.CantServe,
                "remote-ended-cant-reach-tv" to CastProblem.CantReachTv,
                "remote-ended-tv-refused" to CastProblem.TvRefused,
                "remote-ended-lost-tv" to CastProblem.LostTv,
            )
            for ((name, problem) in endings) {
                val ended = starting.copy(phase = CastPhase.Ended, tvState = video, problem = problem)
                Probe.instrumentation.runOnMainSync { AppGraph.casting.showForTest(ended) }
                SendHelpers.waitUntil(name, 5_000) { AppGraph.casting.session.value == ended }
                shot(name)
            }
            Probe.instrumentation.runOnMainSync { AppGraph.casting.showForTest(null) }
            go(sc, Screen.Home)
        }
    }
}
