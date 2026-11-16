package app.ferry.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.ferry.AppGraph
import app.ferry.R
import app.ferry.core.download.DownloadFail
import app.ferry.core.download.DownloadStatus
import app.ferry.core.model.OutFile
import app.ferry.install.ApkInstaller
import app.ferry.ui.common.Format
import app.ferry.ui.common.InstallFlow
import app.ferry.ui.phone.FromPcActions
import app.ferry.ui.phone.downloadText
import java.io.File

/**
 * OK on an APK card (design §5.4 [T4], plan §4.8/§4.9): the APK is downloaded through the outbox download queue
 * (`TransferService`, exact-length rule [PB18]) into `files/apk/<id>.apk` (TV sink, B59), with this progress dialog;
 * when it is complete the dialog closes and [installer] hands it to the system installer ("Allow Ferry to install
 * apps" + settings / text steps first, if needed). A cut-off or failed download never reaches the installer: the
 * dialog shows the reason and **OK**. **Cancel** stops the download.
 */
@Composable
fun TvInstallDialog(file: OutFile, installer: InstallFlow, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val all by AppGraph.downloads.state.collectAsState()
    // The status before this dialog started a download: an older Done of the same id must not be installed again.
    val initial = remember(file.id) { AppGraph.downloads.state.value[file.id] }
    LaunchedEffect(file.id) {
        if (!FromPcActions.active(initial)) {
            AppGraph.log("tv", "install: downloading ${file.name} (${file.size} B, id ${file.id})")
            FromPcActions.download(ctx, listOf(file))
        }
    }
    val st = all[file.id].takeIf { it !== initial } ?: initial.takeIf { FromPcActions.active(it) }
    LaunchedEffect(st) {
        if (st is DownloadStatus.Done) {
            AppGraph.log("tv", "install: ${file.name} downloaded, handing it to the installer")
            onClose()
            installer.install(ApkInstaller.uriFor(ctx, File(st.openUri)))
        }
    }
    val failed = st is DownloadStatus.Failed || st is DownloadStatus.Cancelled
    val button = remember { FocusRequester() }
    LaunchedEffect(failed) { runCatching { button.requestFocus() } }
    AlertDialog(
        onDismissRequest = {
            if (!failed) FromPcActions.cancel(ctx, file.id)
            onClose()
        },
        title = { Text(file.name, maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val line = when {
                    st is DownloadStatus.Failed && st.message == DownloadFail.NOT_ENOUGH_SPACE ->
                        stringResource(R.string.tv_storage_full, Format.size(file.size))
                    st == null -> stringResource(R.string.dl_downloading, 0)
                    else -> downloadText(ctx.resources, st, file) ?: ""
                }
                Text(line, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("tv-install-status"))
                if (!failed) {
                    val r = st as? DownloadStatus.Running
                    LinearProgressIndicator(
                        progress = { if (r != null && r.total > 0) r.got.toFloat() / r.total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            if (failed) {
                TextButton(onClick = onClose, modifier = Modifier.focusRequester(button)) { Text(stringResource(R.string.ok)) }
            } else {
                TextButton(onClick = {
                    FromPcActions.cancel(ctx, file.id)
                    onClose()
                }, modifier = Modifier.focusRequester(button)) { Text(stringResource(R.string.cancel)) }
            }
        },
        modifier = Modifier.testTag("tv-install"),
    )
}

/** `files/apk` is emptied after the installer returns (plan §4.8 [PB18]): a Fire TV Stick has little storage. */
fun emptyApkFolder() {
    val n = File(AppGraph.app.filesDir, "apk").listFiles()?.count { it.delete() } ?: 0
    AppGraph.log("tv", "installer returned: files/apk emptied ($n)")
}
