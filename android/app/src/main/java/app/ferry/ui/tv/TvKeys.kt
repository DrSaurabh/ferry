package app.ferry.ui.tv

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

/**
 * Remote keys for the full-screen TV screens (Player, Viewer/Slideshow; design §5.2/§5.3: "the player screen handles
 * these keys itself"). They are taken in `MainActivity.dispatchKeyEvent` before the view tree (B74), so they never
 * depend on which Compose node or `PlayerView` child holds focus (plan §6 Ph8b risk: key delivery through
 * `PlayerView`). Back is never taken here: each screen's `BackHandler` handles it. Main thread only.
 */
object TvKeys {
    private var handler: ((KeyEvent) -> Boolean)? = null

    /** Called by MainActivity for every key event; true = consumed. */
    fun dispatch(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) return false
        return handler?.invoke(e) ?: false
    }

    /** While this composable is shown, [onKey] gets the remote's keys first. */
    @Composable
    fun Handle(onKey: (KeyEvent) -> Boolean) {
        val current = rememberUpdatedState(onKey)
        DisposableEffect(Unit) {
            val h: (KeyEvent) -> Boolean = { current.value(it) }
            handler = h
            onDispose { if (handler === h) handler = null }
        }
    }

    /** OK on the remote / Enter on a keyboard. */
    fun isOk(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
}
