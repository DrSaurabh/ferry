package app.ferry.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.ferry.AppGraph
import app.ferry.R
import app.ferry.core.download.DownloadStatus
import app.ferry.core.upload.Phase
import app.ferry.install.ApkInstaller
import app.ferry.install.UpdateProgress
import app.ferry.net.UpdateState
import app.ferry.ui.theme.FerryAmber

/**
 * "Ferry 2.0.7 is available → Update" (design §4.6) [C3], phone and TV (focusable). Tap → the APK is downloaded
 * into `files/update/Ferry.apk` (progress in the banner), size and sha256 are checked, then the system installer
 * opens (through [InstallFlow]: "Allow Ferry to install apps" first if needed). While a transfer or download runs the
 * banner says "Update after this finishes" and does nothing [B21] (installing restarts Ferry).
 */
@Composable
fun UpdateBanner(state: UpdateState, modifier: Modifier = Modifier) {
    if (state !is UpdateState.Available) return
    val ctx = LocalContext.current
    val progress by AppGraph.updater.progress.collectAsState()
    val transfer by AppGraph.transfers.state.collectAsState()
    val downloads by AppGraph.downloads.state.collectAsState()
    val busy = (transfer != null && transfer?.phase != Phase.Finished) ||
        downloads.values.any { it is DownloadStatus.Queued || it is DownloadStatus.Running }
    val installer = rememberInstallFlow()
    var installWhenReady by remember { mutableStateOf(false) }
    val p = progress
    LaunchedEffect(p) {
        if (p is UpdateProgress.Ready && installWhenReady) {
            installWhenReady = false
            installer.install(ApkInstaller.uriFor(ctx, p.file))
        }
    }
    var focused by remember { mutableStateOf(false) }
    val text = when {
        busy -> stringResource(R.string.update_after)
        p is UpdateProgress.Downloading -> stringResource(R.string.update_downloading, state.info.versionName,
            if (p.total > 0) (p.got * 100 / p.total).toInt() else 0)
        else -> stringResource(R.string.update_available, state.info.versionName)
    }
    Surface(
        color = FerryAmber,
        contentColor = Color.Black,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("update-banner")
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(enabled = !busy) {
                val now = AppGraph.updater.progress.value
                when {
                    now is UpdateProgress.Downloading -> {}
                    now is UpdateProgress.Ready && now.info == state.info -> installer.install(ApkInstaller.uriFor(ctx, now.file))
                    else -> {
                        AppGraph.log("update", "Update tapped (${state.info.versionName})")
                        installWhenReady = true
                        AppGraph.updater.start(state.info)
                    }
                }
            },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("update-text"))
            if (!busy && p is UpdateProgress.Downloading) {
                LinearProgressIndicator(progress = { if (p.total > 0) p.got.toFloat() / p.total else 0f }, Modifier.fillMaxWidth())
            }
            if (!busy && p is UpdateProgress.Failed) Text(stringResource(R.string.download_failed), style = MaterialTheme.typography.bodyMedium)
        }
    }
    installer.Overlay()
}
