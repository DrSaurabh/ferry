package app.ferry.ui.nav

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/** The app's screens (later phases add Picker, Transfer, FromPc, PlayOnTv, Remote, TV player…). */
sealed interface Screen {
    object Welcome : Screen
    object Home : Screen
    object Settings : Screen
    object Transfer : Screen
    object FromPc : Screen
    data class Picker(val mode: app.ferry.media.PickerMode) : Screen
    /** Phone → TV (Ph9): choose the TV, then Videos / Photo slideshow. */
    object PlayOnTv : Screen
    /** Phone → TV (Ph9): the remote control of the cast that is running. */
    object Remote : Screen
    object TvHome : Screen
    /** The TV video player (Ph8b); [url] is absolute; [resumeKey] = `name|size|mtime` (null: no resume). */
    data class TvPlayer(val name: String, val url: String, val resumeKey: String? = null) : Screen
    /** TV photo viewer (paused at [start]) or slideshow (auto-advance from [start]) (Ph8b). */
    data class TvViewer(val photos: List<app.ferry.ui.tv.TvPhoto>, val start: Int, val slideshow: Boolean) : Screen
}

/** The screen's name in the log (`screen name=`, `back screen=`, `tap … screen=`; design §7.1). */
val Screen.logName: String
    get() = when (this) {
        Screen.Welcome -> "welcome"
        Screen.Home -> "home"
        Screen.Settings -> "settings"
        Screen.Transfer -> "transfer"
        Screen.FromPc -> "from_pc"
        is Screen.Picker -> "picker_" + mode.name.lowercase(java.util.Locale.US)
        Screen.PlayOnTv -> "play_on_tv"
        Screen.Remote -> "remote"
        Screen.TvHome -> "tv_home"
        is Screen.TvPlayer -> "tv_player"
        is Screen.TvViewer -> if (slideshow) "tv_slideshow" else "tv_viewer"
    }

/**
 * A tiny back stack instead of navigation-compose [CP1]. Each screen installs
 * `BackHandler(enabled = stack.size > 1) { pop() }`; on the root, Back finishes the activity.
 * 2.1 (design §7.1): every new top screen is reported to [onScreen] (`screen name=`), every pop to [onBack]
 * (`back screen=<the screen left> result=popped`).
 */
class Navigator(
    start: Screen,
    private val onScreen: (String) -> Unit = {},
    private val onBack: (screen: String, result: String) -> Unit = { _, _ -> },
) {
    val stack: SnapshotStateList<Screen> = mutableStateListOf(start)
    val current: Screen get() = stack.last()

    init {
        onScreen(start.logName)
    }

    fun push(s: Screen) {
        stack.add(s)
        onScreen(s.logName)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        val left = stack.removeAt(stack.lastIndex)
        onBack(left.logName, "popped")
        onScreen(current.logName)
        return true
    }

    /** Shows [s] on top, unless it is already the top screen (notification tap while it is open). */
    fun show(s: Screen) {
        if (current != s) push(s)
    }

    fun replaceAll(s: Screen) {
        stack.clear()
        stack.add(s)
        onScreen(s.logName)
    }
}
