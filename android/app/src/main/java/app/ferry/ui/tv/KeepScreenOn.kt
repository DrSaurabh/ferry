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
 * Screen stays on (design §5.1 [A10] [H24], plan §4.9): `FLAG_KEEP_SCREEN_ON` while the Player / Viewer / Slideshow
 * is showing ([Pinned]); on Home it is set by every key press ([poke], from `MainActivity.dispatchKeyEvent`) and by a
 * cast (Ph9), and cleared 30 min later by a restartable coroutine, so a forgotten TV still goes to sleep.
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

    /** Keeps the screen on while this composable is shown (Player, Viewer, Slideshow); Home's 30 min start after. */
    @Composable
    fun Pinned() {
        val activity = LocalContext.current as? Activity ?: return
        DisposableEffect(activity) {
            pinned++
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose {
                pinned--
                poke(activity)
            }
        }
    }
}
