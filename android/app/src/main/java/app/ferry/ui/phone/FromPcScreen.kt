package app.ferry.ui.phone

import app.ferry.ui.common.onClickLogged
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ferry.AppGraph
import app.ferry.R
import app.ferry.core.FerryJson
import app.ferry.core.download.DownloadFail
import app.ferry.core.download.DownloadStatus
import app.ferry.core.model.OutFile
import app.ferry.install.ApkInstaller
import app.ferry.media.DownloadSinks
import app.ferry.net.ConnectionState
import app.ferry.service.TransferService
import app.ferry.ui.common.Format
import app.ferry.ui.common.InstallFlow
import app.ferry.ui.common.rememberInstallFlow
import app.ferry.ui.common.statusText
import app.ferry.ui.nav.Navigator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

/** From PC state (plan §4.3): the outbox list, the download queue, the connection. */
class OutboxViewModel : ViewModel() {
    val connection = AppGraph.connection.state
    val outbox = AppGraph.outbox
    val downloads = AppGraph.downloads.state
    val waiting = AppGraph.downloads.waitingForPc

    suspend fun refresh() = AppGraph.refreshOutbox()
}

/** Starting and cancelling downloads: work runs in `TransferService` (survives the screen off, design §4.5). */
object FromPcActions {
    fun download(ctx: Context, files: List<OutFile>) {
        if (files.isEmpty()) return
        AppGraph.log("download", "download tapped: ${files.joinToString { it.name }}")
        val json = FerryJson.encodeToString(ListSerializer(OutFile.serializer()), files)
        ContextCompat.startForegroundService(
            ctx,
            Intent(ctx, TransferService::class.java).setAction(TransferService.ACTION_DOWNLOAD).putExtra(TransferService.EXTRA_FILES, json),
        )
    }

    fun cancel(ctx: Context, id: Int) {
        ContextCompat.startForegroundService(
            ctx,
            Intent(ctx, TransferService::class.java).setAction(TransferService.ACTION_CANCEL_DOWNLOADS).putExtra(TransferService.EXTRA_FILE_ID, id),
        )
    }

    /** "Open" (ACTION_VIEW with a read grant [A16]) or "Install" for APKs (system installer, design §4.6). */
    fun open(ctx: Context, f: OutFile, uri: String, installer: InstallFlow) {
        val u = Uri.parse(uri)
        if (f.kind == "apk" || f.name.endsWith(".apk", ignoreCase = true)) {
            installer.install(u)
            return
        }
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(u, DownloadSinks.mimeFor(f.name))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, R.string.no_app_to_open, Toast.LENGTH_LONG).show()
        }
    }

    /** Busy: queued or running. */
    fun active(st: DownloadStatus?) = st is DownloadStatus.Queued || st is DownloadStatus.Running
}

/**
 * From PC (design §4.1.4, plan §4.3) [P7]: the PC's outbox (kind, name, size, age), refreshed every 5 s while
 * visible and with a Refresh button (no pull-to-refresh, plan's fallback [PA15]); tap a file → download to
 * `Download/Ferry` (progress per row); **Download all**; then **Open** / **Install**. On Android 9 and older the list
 * is shown with "Downloading from the PC needs Android 10 or newer." and no download action [CP17].
 */
@Composable
fun FromPcScreen(nav: Navigator, vm: OutboxViewModel = viewModel()) {
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    val ctx = LocalContext.current
    val conn by vm.connection.collectAsState()
    val outbox by vm.outbox.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val waiting by vm.waiting.collectAsState()
    val installer = rememberInstallFlow()
    val scope = rememberCoroutineScope()
    var asked by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val connected = conn as? ConnectionState.Connected
    LaunchedEffect(connected?.api) {
        if (connected == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.refresh()
                delay(5_000)
            }
        }
    }
    val canDownload = DownloadSinks.supported
    val files = if (connected != null) outbox?.files.orEmpty() else emptyList()
    val start = { list: List<OutFile> ->
        asked = true
        FromPcActions.download(ctx, list)
    }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp).testTag("from-pc"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.from_pc_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClickLogged("from_pc.refresh") { scope.launch { vm.refresh() } }) { Text(stringResource(R.string.refresh)) }
            if (nav.stack.size > 1) TextButton(onClick = onClickLogged("from_pc.back") { nav.pop() }) { Text(stringResource(R.string.back)) }
        }
        if (connected == null) {
            Text(statusText(conn), style = MaterialTheme.typography.bodyLarge)
        } else if (canDownload) {
            Text(stringResource(R.string.from_pc_where, connected.name), style = MaterialTheme.typography.bodyMedium)
        }
        if (!canDownload) {
            Text(stringResource(R.string.downloads_need_android10), Modifier.testTag("need-android10"),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        }
        if (waiting) Text(stringResource(R.string.waiting_for_pc, connected?.name ?: stringResource(R.string.your_pc)))
        if (connected != null && outbox != null && files.isEmpty()) {
            Text(stringResource(R.string.from_pc_empty), style = MaterialTheme.typography.bodyLarge)
        }
        if (canDownload && files.size > 1) {
            Button(onClick = onClickLogged("from_pc.download_all") { start(files.filter { !FromPcActions.active(downloads[it.id]) }) }, Modifier.testTag("download-all")) {
                Text(stringResource(R.string.download_all))
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(files, key = { it.id }) { f ->
                OutboxRow(f, downloads[f.id], canDownload,
                    onDownload = { start(listOf(f)) },
                    onCancel = { FromPcActions.cancel(ctx, f.id) },
                    onOpen = { uri -> FromPcActions.open(ctx, f, uri, installer) })
            }
        }
    }
    if (asked) AskNotificationsOnce() // POST_NOTIFICATIONS when the first download starts [H23]
    installer.Overlay()
}

@Composable
private fun OutboxRow(
    f: OutFile,
    st: DownloadStatus?,
    canDownload: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val active = FromPcActions.active(st)
    Row(
        Modifier.fillMaxWidth().testTag("outbox-${f.id}")
            .clickable(enabled = canDownload && !active, onClick = onClickLogged("from_pc.download", onDownload)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindBadge(f)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            val age = Format.age(f.mtime, System.currentTimeMillis())
            Text(Format.size(f.size) + (if (age.isNotEmpty()) " · $age" else ""),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val line = downloadText(ctx.resources, st, f)
            if (line != null) Text(line, Modifier.testTag("dl-status-${f.id}"), style = MaterialTheme.typography.bodyMedium,
                color = if (st is DownloadStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            if (st is DownloadStatus.Running) {
                LinearProgressIndicator(progress = { if (st.total > 0) st.got.toFloat() / st.total else 0f }, Modifier.fillMaxWidth())
            }
        }
        when {
            active -> TextButton(onClick = onClickLogged("from_pc.cancel", onCancel)) { Text(stringResource(R.string.cancel)) }
            st is DownloadStatus.Done && st.openUri.isNotEmpty() -> OutlinedButton(onClick = onClickLogged("from_pc.open") { onOpen(st.openUri) }, Modifier.testTag("open-${f.id}")) {
                Text(stringResource(if (f.kind == "apk") R.string.install else R.string.open))
            }
        }
    }
}

/** A small label by kind (PDF, MP4, JPG…): no icon library needed. */
@Composable
private fun KindBadge(f: OutFile) {
    val ext = f.name.substringAfterLast('.', "").uppercase().take(4).ifEmpty { "FILE" }
    val color = when (f.kind) {
        "image" -> MaterialTheme.colorScheme.tertiaryContainer
        "video", "video_playable" -> MaterialTheme.colorScheme.secondaryContainer
        "apk" -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(color = color, shape = RoundedCornerShape(6.dp), modifier = Modifier.size(52.dp)) {
        Box(contentAlignment = Alignment.Center) { Text(ext, style = MaterialTheme.typography.labelLarge) }
    }
}

/** The row's download line (plan §4.12 + Ph7 strings); null when it was never downloaded. */
fun downloadText(res: Resources, st: DownloadStatus?, f: OutFile): String? = when (st) {
    null -> null
    DownloadStatus.Queued -> res.getString(R.string.row_waiting)
    is DownloadStatus.Running -> res.getString(R.string.dl_downloading, if (st.total > 0) (st.got * 100 / st.total).toInt() else 0)
    is DownloadStatus.Done -> res.getString(R.string.dl_saved_as, st.savedAs)
    DownloadStatus.Cancelled -> res.getString(R.string.row_cancelled)
    is DownloadStatus.Failed -> when (st.message) {
        DownloadFail.NOT_ENOUGH_SPACE -> res.getString(R.string.phone_storage_full, Format.size(f.size))
        DownloadFail.PC_NOT_FOUND -> res.getString(R.string.dl_failed_pc)
        DownloadFail.KEPT_DROPPING -> res.getString(R.string.row_failed_drops)
        DownloadFail.GONE_FROM_PC -> res.getString(R.string.dl_failed_gone)
        DownloadFail.CHANGED_ON_PC -> res.getString(R.string.dl_failed_changed)
        else -> res.getString(R.string.download_failed)
    }
}
