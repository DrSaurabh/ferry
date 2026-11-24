package app.ferry.ui.phone

import app.ferry.ui.common.LogDialog
import app.ferry.ui.common.logDialog
import app.ferry.ui.common.onClickLogged
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ferry.AppGraph
import app.ferry.R
import app.ferry.core.upload.EngineState
import app.ferry.core.upload.ItemRow
import app.ferry.core.upload.ItemStatus
import app.ferry.core.upload.Phase
import app.ferry.media.SendCoordinator
import app.ferry.ui.common.Format
import app.ferry.ui.common.PermissionPlanner
import app.ferry.ui.common.TransferMath
import app.ferry.ui.common.TransferWords
import app.ferry.ui.nav.Navigator

class TransferViewModel : ViewModel() {
    val state = AppGraph.transfers.state
    val retryLost = AppGraph.transfers.retryLost
}

/**
 * Transfer screen (design §4.1.3, plan §4.3): overall bar, "Sending 5 of 20 · 18.2 MB/s · about 1 min left",
 * per-file rows (§4.12 wording), Cancel with a confirmation, "Waiting for <PC>… (continues automatically)", and at the
 * end the summary with **Retry** and **Send anyway** [A2]. Notifications are asked for here, when the first
 * transfer starts [H23].
 */
@Composable
fun TransferScreen(nav: Navigator, vm: TransferViewModel = viewModel()) {
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    val ctx = LocalContext.current
    val s by vm.state.collectAsState()
    val words = remember(ctx) { TransferWords(ctx.resources) }
    AskNotificationsOnce()
    var confirmCancel by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp).testTag("transfer"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.transfer_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            if (nav.stack.size > 1) TextButton(onClick = onClickLogged("transfer.back") { nav.pop() }) { Text(stringResource(R.string.back)) }
        }
        val st = s
        if (st == null) {
            val lost by vm.retryLost.collectAsState()
            Text(
                stringResource(if (lost) R.string.transfer_retry_lost else R.string.transfer_nothing),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag(if (lost) "transfer-retry-lost" else "transfer-nothing"),
            )
        } else {
            TransferBody(st, words, onCancel = { confirmCancel = true })
        }
    }
    if (confirmCancel) {
        LogDialog("cancel_confirm")
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.cancel_confirm_title)) },
            text = { Text(stringResource(R.string.cancel_confirm_text)) },
            confirmButton = {
                TextButton(onClick = onClickLogged("cancel_confirm.yes") { logDialog("cancel_confirm", "yes"); confirmCancel = false; SendCoordinator.cancel(ctx) }, Modifier.testTag("cancel-confirm")) {
                    Text(stringResource(R.string.cancel_confirm_yes))
                }
            },
            dismissButton = { TextButton(onClick = onClickLogged("cancel_confirm.no") { logDialog("cancel_confirm", "no"); confirmCancel = false }) { Text(stringResource(R.string.cancel_confirm_no)) } },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.TransferBody(st: EngineState, words: TransferWords, onCancel: () -> Unit) {
    val running = st.phase != Phase.Finished
    Text(words.headline(st), Modifier.testTag("transfer-headline"), style = MaterialTheme.typography.titleMedium)
    val detail = words.detail(st)
    if (detail.isNotEmpty()) Text(detail, Modifier.testTag("transfer-detail"), style = MaterialTheme.typography.bodyMedium)
    if (running) {
        LinearProgressIndicator(progress = { TransferMath.fraction(st) }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = onClickLogged("transfer.cancel", onCancel), Modifier.testTag("transfer-cancel")) { Text(stringResource(R.string.cancel)) }
    } else {
        SummaryCard(st, words)
    }
    HorizontalDivider()
    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(st.items, key = { it.id }) { row -> RowLine(row, words, finished = !running) }
    }
}

@Composable
private fun SummaryCard(st: EngineState, words: TransferWords) {
    val sum = st.summary ?: return
    val ctx = LocalContext.current
    // The summary line itself is the headline above; the card holds the extras and the actions.
    val extras = words.summaryExtras(sum, words.pcName(st))
    val already = st.items.filter { it.status is ItemStatus.AlreadyOnPc }.map { it.id }
    if (extras.isEmpty() && already.isEmpty() && TransferMath.retryable(sum) == 0) return
    Card(Modifier.fillMaxWidth().testTag("transfer-summary")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (line in extras) Text(line, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (TransferMath.retryable(sum) > 0) {
                    Button(onClick = onClickLogged("transfer.retry") { SendCoordinator.retry(ctx) }, Modifier.testTag("transfer-retry")) { Text(stringResource(R.string.retry)) }
                }
                if (already.isNotEmpty()) {
                    OutlinedButton(onClick = onClickLogged("transfer.send_anyway_all") { SendCoordinator.sendAnyway(ctx, already) }, Modifier.testTag("send-anyway-all")) {
                        Text(stringResource(R.string.send_anyway))
                    }
                }
            }
        }
    }
}

@Composable
private fun RowLine(row: ItemRow, words: TransferWords, finished: Boolean) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(Format.size(row.size) + " · " + words.row(row), Modifier.testTag("row-status-${row.id}"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (finished && row.status is ItemStatus.AlreadyOnPc) {
            TextButton(onClick = onClickLogged("transfer.send_anyway") { SendCoordinator.sendAnyway(ctx, listOf(row.id)) }) { Text(stringResource(R.string.send_anyway)) }
        }
    }
}

/** POST_NOTIFICATIONS once, when the first transfer starts (design §4.2) [H23]; a refusal changes nothing else. */
@Composable
fun AskNotificationsOnce() {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(PermissionPlanner.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (PermissionPlanner.needsNotificationRequest(Build.VERSION.SDK_INT, granted, AppGraph.prefs.notificationsAsked)) {
            AppGraph.prefs.notificationsAsked = true
            launcher.launch(PermissionPlanner.POST_NOTIFICATIONS)
        }
    }
}
