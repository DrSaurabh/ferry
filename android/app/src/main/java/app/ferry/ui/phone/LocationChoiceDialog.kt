package app.ferry.ui.phone

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.ferry.R

/**
 * "Ferry isn't allowed to read photo locations. Allow / Send without location" (design §4.3, plan §4.3) [H19]. Shown
 * by the screen that called `SendCoordinator.prepare()` (picker, "Send other files", share activity) before anything
 * is queued [PB5]: Allow → request ACCESS_MEDIA_LOCATION and prepare again; Send without location →
 * `SendCoordinator.withoutLocation()`.
 */
@Composable
fun LocationChoiceDialog(onAllow: () -> Unit, onWithoutLocation: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("location-dialog"),
        text = { Text(stringResource(R.string.location_dialog)) },
        confirmButton = { TextButton(onClick = onAllow) { Text(stringResource(R.string.location_allow)) } },
        dismissButton = { TextButton(onClick = onWithoutLocation) { Text(stringResource(R.string.location_without)) } },
    )
}
