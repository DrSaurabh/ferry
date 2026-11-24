package app.ferry.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.ferry.AppGraph
import app.ferry.R

/**
 * "Something's wrong" (L4, design §7.1): one marker in the log with the screen it was opened on and an optional
 * short note — `wrong screen=<screen> note="…"`. "Skip" (or Back) writes the marker without a note.
 */
@Composable
fun WrongDialog(screen: String, onDone: () -> Unit) {
    var note by rememberSaveable { mutableStateOf("") }
    LogDialog("wrong")
    fun finish(withNote: Boolean) {
        val n = if (withNote) note.trim().take(500) else ""
        logDialog("wrong", if (withNote && n.isNotEmpty()) "note" else "skip")
        AppGraph.event("wrong", "screen" to screen, "note" to n)
        onDone()
    }
    AlertDialog(
        onDismissRequest = { finish(false) },
        modifier = Modifier.testTag("wrong-dialog"),
        title = { Text(stringResource(R.string.wrong_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.wrong_text))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(500) },
                    label = { Text(stringResource(R.string.wrong_note_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag("wrong-note"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onClickLogged("wrong.save") { finish(true) }, Modifier.testTag("wrong-save")) {
                Text(stringResource(R.string.wrong_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onClickLogged("wrong.skip") { finish(false) }, Modifier.testTag("wrong-skip")) {
                Text(stringResource(R.string.wrong_skip))
            }
        },
    )
}
