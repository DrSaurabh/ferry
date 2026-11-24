package app.ferry.ui.phone

import app.ferry.ui.common.LogDialog
import app.ferry.ui.common.logDialog
import app.ferry.ui.common.onClickLogged
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
fun LocationChoiceDialog(
    onAllow: () -> Unit,
    onWithoutLocation: () -> Unit,
    onDismiss: () -> Unit,
    /** Allow was already tried without success (audit O1): explain the Settings path and offer **Open Settings**. */
    settings: Boolean = false,
    onOpenSettings: () -> Unit = {},
) {
    LogDialog(if (settings) "location_settings" else "location")
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(if (settings) "location-dialog-settings" else "location-dialog"),
        text = { Text(stringResource(if (settings) R.string.location_settings else R.string.location_dialog)) },
        confirmButton = {
            if (settings) TextButton(onClick = onClickLogged("location.open_settings") { logDialog("location_settings", "open_settings"); onOpenSettings() }) { Text(stringResource(R.string.open_settings)) }
            else TextButton(onClick = onClickLogged("location.allow") { logDialog("location", "allow"); onAllow() }) { Text(stringResource(R.string.location_allow)) }
        },
        dismissButton = { TextButton(onClick = onClickLogged("location.without") { logDialog(if (settings) "location_settings" else "location", "without"); onWithoutLocation() }) { Text(stringResource(R.string.location_without)) } },
    )
}
