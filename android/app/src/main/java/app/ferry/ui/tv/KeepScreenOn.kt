package app.ferry.ui.tv

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import app.ferry.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Screen stays on (design §5.1 [A10] [H24], plan §4.9): `FLAG_KEEP_SCREEN_ON` while a video is **playing** or a
 * slideshow is **running** ([Pinned] with `active = true`); everywhere else it is set by every key press ([poke], from
 * `MainActivity.dispatchKeyEvent`) and by a cast (Ph9), and cleared 30 min later by a restartable coroutine, so a
 * forgotten TV still goes to sleep.
 *
 * Audit O4 (H41): a paused or finished video, a paused slideshow and a single photo follow the 30-minute rule too
 * (they used to keep the TV awake for as long as they were on screen, i.e. all night on a forgotten TV).
 */
object KeepScreenOn {
    const val HOME_MS = 30L * 60 * 1000

    private var timer: Job? = null
    private var pinned = 0

    /** Main thread. A key press or a cast: on now, off 30 min after the last one (unless a player is showing). */
    fun poke(activity: Activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        timer?.cancel()
        val ref = WeakReference(activity)
        timer = AppGraph.scope.launch(Dispatchers.Main) {
            delay(HOME_MS)
            if (pinned == 0) {
                ref.get()?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                AppGraph.log("tv", "keep-screen-on cleared after 30 min idle")
            }
        }
    }

    /** True while the window has the flag (tests). */
    fun isOn(activity: Activity): Boolean =
        activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    /** Screens that pin the screen on right now (tests: 1 while playing, 0 when paused). */
    val pinnedNow: Int get() = pinned

    /**
     * Keeps the screen on while this composable is shown **and** [active] (video playing, slideshow running). When
     * it stops being active or leaves the screen, the 30 minutes start (as after a key press).
     */
    @Composable
    fun Pinned(active: Boolean) {
        val activity = LocalContext.current as? Activity ?: return
        DisposableEffect(activity, active) {
            if (active) {
                pinned++
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                poke(activity)
            }
            onDispose {
                if (active) {
                    pinned--
                    poke(activity)
                }
            }
        }
    }
}
