package app.ferry.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.ferry.AppGraph

/**
 * Tap ids, dialogs and shown errors in the event log (design §7.1, V11, plan CP111/CP129). Every clickable in `ui/`
 * passes an id — `onClick = onClickLogged("home.settings") { … }`, TV cards `tvClickable(id = …)` — which
 * `TapIdLintTest` checks. Ids are `<screen>.<what>`, lower case.
 */
object TapLog {
    /** The screen on top (set by the Navigator's `screen` event); added to `tap` lines as `screen=`. */
    @Volatile var screen: String = ""

    fun tap(id: String) {
        AppGraph.event("tap", "id" to id, "screen" to screen)
    }
}

/** A click handler that logs `tap id=<id>` first. */
fun onClickLogged(id: String, block: () -> Unit): () -> Unit = {
    TapLog.tap(id)
    block()
}

/** Logs `dialog name=<name> shown=1` once while this dialog is shown (O8). */
@Composable
fun LogDialog(name: String) {
    LaunchedEffect(name) { AppGraph.event("dialog", "name" to name, "shown" to 1) }
}

/** The user's answer to a dialog: `dialog name=<name> choice=<choice>` (O8). */
fun logDialog(name: String, choice: String) {
    AppGraph.event("dialog", "name" to name, "choice" to choice)
}

/** A user-visible error text: `err.shown text="…" screen=` once per text while it is shown (V11). */
@Composable
fun LogError(text: String?) {
    if (text.isNullOrEmpty()) return
    LaunchedEffect(text) { AppGraph.event("err.shown", "text" to text, "screen" to TapLog.screen) }
}

/** A screen that is not a Navigator screen (a cast shown on the TV, the loopback view): `screen name=` once. */
@Composable
fun LogScreen(name: String) {
    LaunchedEffect(name) {
        TapLog.screen = name
        AppGraph.event("screen", "name" to name)
    }
}
