package app.ferry.ui.common

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.ferry.R

/**
 * The one top bar of every phone screen (CP138, PO5, design §5.1): **Back** (when [showBack]) goes through the
 * activity's back dispatcher — the same path as the system Back (H69), so each screen's `BackHandler` decides — then
 * the title, the screen's own [actions], and ⋮ with "Something's wrong" (L4, `wrong screen=<this screen>`).
 */
@Composable
fun PhoneTopBar(title: String, showBack: Boolean, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    PhoneTopBar(showBack, modifier, actions) {
        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("top-title"))
    }
}

@Composable
fun PhoneTopBar(
    showBack: Boolean,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    title: @Composable RowScope.() -> Unit,
) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (showBack) {
            TextButton(onClick = onClickLogged(TapLog.screen + ".back") { dispatcher?.onBackPressed() }, Modifier.testTag("top-back")) {
                Text("← " + stringResource(R.string.back))
            }
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { title() }
        actions()
        TopOverflow()
    }
}

/** ⋮ → "Something's wrong" (L4): the marker carries the screen it was opened on. */
@Composable
private fun TopOverflow() {
    var open by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    val more = stringResource(R.string.wrong_more)
    Box {
        TextButton(onClick = onClickLogged(TapLog.screen + ".overflow") { open = true }, Modifier.testTag("top-overflow")) {
            Text("⋮", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = more })
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.wrong_menu)) },
                onClick = onClickLogged(TapLog.screen + ".wrong") {
                    open = false
                    wrong = true
                },
                modifier = Modifier.testTag("menu-wrong"),
            )
        }
    }
    if (wrong) WrongDialog(TapLog.screen) { wrong = false }
}
