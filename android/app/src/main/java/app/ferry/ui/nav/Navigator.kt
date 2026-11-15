package app.ferry.ui.nav

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/** The app's screens (later phases add Picker, Transfer, FromPc, PlayOnTv, Remote, TV player…). */
sealed interface Screen {
    object Welcome : Screen
    object Home : Screen
    object Settings : Screen
    object TvHome : Screen
}

/**
 * A tiny back stack instead of navigation-compose [CP1]. Each screen installs
 * `BackHandler(enabled = stack.size > 1) { pop() }`; on the root, Back finishes the activity.
 */
class Navigator(start: Screen) {
    val stack: SnapshotStateList<Screen> = mutableStateListOf(start)
    val current: Screen get() = stack.last()

    fun push(s: Screen) {
        stack.add(s)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun replaceAll(s: Screen) {
        stack.clear()
        stack.add(s)
    }
}
