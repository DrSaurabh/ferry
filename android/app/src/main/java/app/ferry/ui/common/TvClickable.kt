package app.ferry.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import app.ferry.ui.theme.FerryAmber
import kotlinx.coroutines.delay

/**
 * The one way a TV card takes focus and clicks (plan §4.9 [PA14]): `onFocusChanged` (focus ring 3 dp amber +
 * scale 1.05, and [onFocus] for the focus memory) + **`clickable(onClick)` only**. `clickable` is itself the focus
 * target and already turns D-pad centre / Enter into one click, so there is no extra `focusable()` (two focus stops
 * per card) and no own key handler (two clicks per OK). `TvLaunchAndFocusTest` checks both.
 * 2.1: [id] is the tap id logged on OK (`tap id=`, CP129).
 */
fun Modifier.tvClickable(id: String, shape: Shape, onFocus: (Boolean) -> Unit = {}, onClick: () -> Unit): Modifier = composed { // tap-id: id
    var focused by remember { mutableStateOf(false) }
    this
        .scale(if (focused) 1.05f else 1f)
        .onFocusChanged {
            if (it.isFocused != focused) {
                focused = it.isFocused
                onFocus(it.isFocused)
            }
        }
        .border(3.dp, if (focused) FerryAmber else Color.Transparent, shape)
        .clickable(onClick = onClickLogged(id, onClick))
}

/**
 * The same focus ring for Material buttons on the TV ("PC not found", dialogs): their own focus overlay is hard to
 * see from the sofa (Ph8a screenshots), so they get the amber 3 dp outline as well. Put it first in the button's
 * modifier; it watches the button's own focus target.
 */
fun Modifier.tvFocusRing(shape: Shape, onFocus: (Boolean) -> Unit = {}): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged {
            if (it.hasFocus != focused) {
                focused = it.hasFocus
                onFocus(it.hasFocus)
            }
        }
        .border(3.dp, if (focused) FerryAmber else Color.Transparent, shape)
}

/**
 * Gives [requester] focus once it is attached (plan §6 Ph8b risks: "if `requestFocus` races composition, retry
 * after another frame"): up to 10 frames, until [isFocused] says it worked.
 */
suspend fun requestFocusSoon(requester: FocusRequester, isFocused: () -> Boolean) {
    for (i in 1..10) {
        runCatching { requester.requestFocus() }
        withFrameNanos {}
        if (isFocused()) return
        delay(50)
    }
}
