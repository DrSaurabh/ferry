package app.ferry

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import app.ferry.probe.Probe
import app.ferry.ui.nav.Screen
import app.ferry.ui.tv.RowFocus
import app.ferry.ui.tv.TvViewModel

/**
 * Shared steps of the TV classes (Ph8a): the remote is `UiDevice.pressDPad*` / `pressBack`; what has focus is read
 * from the app's own state ([TvViewModel.current], set by the cards' `onFocusChanged`) and the back stack from
 * `MainActivity.nav`, not through a Compose test rule (the home screen refreshes the outbox from IO every 5 s, B50).
 */
class TvHelpers(private val sc: ActivityScenario<MainActivity>) {
    companion object {
        /**
         * A TV class starts as a remote user would: not in touch mode. Touch mode is global and survives the previous
         * class (TvInstallTest taps a dialog button through UiAutomator); in it, Compose refuses `requestFocus`, so no
         * card would have focus until the first key press (Ph8a run 2, B73). Call before launching.
         */
        fun leaveTouchMode() {
            Probe.instrumentation.setInTouchMode(false)
            Probe.record("touch_mode", "left")
        }
    }

    val vm: TvViewModel = run {
        var v: TvViewModel? = null
        sc.onActivity { a -> v = ViewModelProvider(a)[TvViewModel::class.java] }
        checkNotNull(v)
    }

    val current: RowFocus? get() = vm.current
    val title: String? get() = vm.currentTitle

    fun screen(): Screen? {
        var s: Screen? = null
        sc.onActivity { a -> s = a.nav?.current }
        return s
    }

    fun stackSize(): Int {
        var n = 0
        sc.onActivity { a -> n = a.nav?.stack?.size ?: 0 }
        return n
    }

    private fun describe() = "focus=${vm.current} '${vm.currentTitle}' banner=${vm.bannerFocused} screen=${screen()}"

    /** Waits for [cond] on the focus, recording the focus on every press (job log evidence). */
    fun waitFocus(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        SendHelpers.waitUntil("$what (${describe()})", timeoutMs, cond)
    }

    /** One key press, then the focus must have moved to [expect] (and still be there 400 ms later: no double move). */
    fun press(key: String, expect: (RowFocus?) -> Boolean) {
        val before = describe()
        val d = SendHelpers.device
        when (key) {
            "right" -> d.pressDPadRight()
            "left" -> d.pressDPadLeft()
            "up" -> d.pressDPadUp()
            "down" -> d.pressDPadDown()
            "center" -> d.pressDPadCenter()
            "back" -> d.pressBack()
            else -> error(key)
        }
        waitFocus("after $key from $before") { expect(vm.current) }
        Thread.sleep(400)
        val after = describe()
        Probe.record("dpad", "$key: $before → $after")
        check(expect(vm.current)) { "focus moved on after $key: $after" }
    }
}
