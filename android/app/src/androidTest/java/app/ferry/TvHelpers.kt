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

    fun describe() = "focus=${vm.current} '${vm.currentTitle}' banner=${vm.bannerFocused} screen=${screen()}"

    /** Waits for [cond] on the focus, recording the focus on every press (job log evidence). */
    fun waitFocus(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        SendHelpers.waitUntil("$what (${describe()})", timeoutMs, cond)
    }

    /** The outbox contains every name in [names] (the TV lists it every 5 s). */
    fun waitOutbox(vararg names: String) {
        SendHelpers.waitUntil("outbox lists ${names.toList()}", 30_000) {
            val have = vm.outbox.value?.files?.map { it.name }.orEmpty()
            names.all { it in have }
        }
    }

    /** From the first visit's focus (Videos card 0), moves right along [row] until the card titled [title] has focus. */
    fun focusCard(row: String, title: String) {
        waitFocus("a card focused", 20_000) { current != null }
        if (current?.row != row) {
            check(row == "photos") { "focusCard supports videos and photos" }
            press("down") { it?.row == "photos" }
        }
        repeat(12) {
            if (this.title == title) return
            val i = current!!.index
            press("right") { it == RowFocus(row, i + 1) }
        }
        check(this.title == title) { "no card '$title' in $row (focus ${describe()})" }
    }

    /** Moves left along the focused row to its first card. */
    fun focusFirstInRow() {
        while (current!!.index > 0) {
            val r = current!!.row
            val i = current!!.index
            press("left") { it == RowFocus(r, i - 1) }
        }
    }

    /** OK on the focused card; [expect] is the screen it must open. */
    fun ok(what: String, expect: (Screen?) -> Boolean) {
        SendHelpers.device.pressDPadCenter()
        SendHelpers.waitUntil(what, 10_000) { expect(screen()) }
    }

    /** Reads player / viewer state on the main thread (ExoPlayer must only be touched there). */
    fun <T> main(block: () -> T): T {
        var r: T? = null
        Probe.instrumentation.runOnMainSync { r = block() }
        @Suppress("UNCHECKED_CAST")
        return r as T
    }

    /** A remote key by key code (media keys, D-pad), as the Fire TV remote sends it. */
    fun key(code: Int) {
        SendHelpers.device.pressKeyCode(code)
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
