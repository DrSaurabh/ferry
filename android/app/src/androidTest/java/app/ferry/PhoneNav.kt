package app.ferry

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.By
import app.ferry.media.PickerMode
import app.ferry.probe.Probe
import app.ferry.ui.nav.Screen
import app.ferry.ui.phone.PickerViewModel

/**
 * Ph13 helpers for the Back tests: the top screen and the picker's state read from the app (B57: UiAutomator's view of
 * Compose text can lag, so assertions are on app state; text is only used to find what to tap).
 */
object PhoneNav {
    private val ctx get() = Probe.context
    val topBack: String get() = "← " + ctx.getString(R.string.back)
    val more: String get() = ctx.getString(R.string.wrong_more)

    fun screen(sc: ActivityScenario<MainActivity>): Screen? {
        var s: Screen? = null
        sc.onActivity { s = it.nav?.current }
        return s
    }

    fun waitScreen(sc: ActivityScenario<MainActivity>, want: Screen, what: String = "screen $want") =
        SendHelpers.waitUntil(what, 10_000) { screen(sc) == want }

    fun picker(sc: ActivityScenario<MainActivity>, mode: PickerMode = PickerMode.Send): PickerViewModel {
        var vm: PickerViewModel? = null
        sc.onActivity { a -> vm = ViewModelProvider(a)["picker-$mode", PickerViewModel::class.java] }
        return checkNotNull(vm)
    }

    /** The picked file names, in pick order. */
    fun selection(sc: ActivityScenario<MainActivity>): List<String?> {
        val vm = picker(sc)
        return vm.selection.value.map { vm.library.value.byId[it]?.name }
    }

    fun waitSelection(sc: ActivityScenario<MainActivity>, vararg want: String) {
        try {
            SendHelpers.waitUntil("selection ${want.toList()}", 15_000) { selection(sc) == want.toList() }
        } finally {
            Probe.record("picker_selection", selection(sc))
        }
    }

    /** The ⋮ of the top bar is on screen (L4, CP138). */
    fun waitOverflow(name: String) =
        SendHelpers.waitUntil("⋮ on $name", 10_000) { SendHelpers.device.hasObject(By.desc(more)) }

    /** Taps the top Back until [done] (the expected result) holds. */
    fun tapTopBack(done: () -> Boolean) {
        SendHelpers.settle() // the screen just pushed may still be replacing the previous one
        SendHelpers.tapUntil("top Back", By.text(topBack), done)
    }

    /** Opens ⋮ (its "Something's wrong" item shows). */
    fun tapMore() {
        SendHelpers.settle()
        val item = ctx.getString(R.string.wrong_menu)
        SendHelpers.tapUntil("⋮", By.desc(more)) { SendHelpers.device.hasObject(By.text(item)) }
    }

    fun discardText(n: Int): String = ctx.resources.getQuantityString(R.plurals.discard_picks, n, n)
}
