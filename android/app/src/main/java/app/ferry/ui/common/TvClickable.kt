package app.ferry.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import app.ferry.ui.theme.FerryAmber

/**
 * The one way a TV card takes focus and clicks (plan §4.9 [PA14]): `onFocusChanged` (focus ring 3 dp amber +
 * scale 1.05, and [onFocus] for the focus memory) + **`clickable(onClick)` only**. `clickable` is itself the focus
 * target and already turns D-pad centre / Enter into one click, so there is no extra `focusable()` (two focus stops
 * per card) and no own key handler (two clicks per OK). `TvLaunchAndFocusTest` checks both.
 */
fun Modifier.tvClickable(shape: Shape, onFocus: (Boolean) -> Unit = {}, onClick: () -> Unit): Modifier = composed {
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
        .clickable(onClick = onClick)
}
