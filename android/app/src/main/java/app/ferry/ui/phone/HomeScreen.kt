package app.ferry.ui.phone

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ferry.AppGraph
import app.ferry.R
import app.ferry.cast.CastPhase
import app.ferry.core.upload.Phase
import app.ferry.net.ConnectionState
import app.ferry.ui.common.TransferMath
import app.ferry.ui.common.TransferWords
import app.ferry.ui.common.StatusDot
import app.ferry.ui.common.UpdateBanner
import app.ferry.ui.common.rememberSendFlow
import app.ferry.media.SendRequest
import app.ferry.ui.common.statusText
import app.ferry.media.PickerMode
import app.ferry.ui.common.MediaAccess
import app.ferry.ui.common.PartialAccessBanner
import app.ferry.ui.common.mediaAccess
import app.ferry.ui.common.rememberMediaGate
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.ferry.ui.nav.Navigator
import app.ferry.ui.nav.Screen

class HomeViewModel : ViewModel() {
    val connection = AppGraph.connection.state
    val update = AppGraph.update
    val transfer = AppGraph.transfers.state
    val interrupted = AppGraph.interrupted
    val outbox = AppGraph.outbox
    val cast = AppGraph.casting.session
    fun tryAgain() = AppGraph.connection.refresh()

    /** The user has seen the interrupted-transfer banner [A8]. */
    fun dismissInterrupted() {
        AppGraph.interrupted.value = 0
        if (!AppGraph.transfers.busy) AppGraph.prefs.interruptedCount = 0
    }
}

/**
 * Home (design §4.1, plan §4.3): "→ PC" + status, the four big buttons, banners. "Send other files" works from
 * Ph5b, "Send photos & videos" (permission flow → picker) from Ph6, "From PC" (with the outbox count) and the update
 * banner from Ph7, "Play on TV" (+ a card back to the running cast's Remote) from Ph9.
 */
@Composable
fun HomeScreen(nav: Navigator, vm: HomeViewModel = viewModel()) {
    val ctx = LocalContext.current
    val conn by vm.connection.collectAsState()
    val update by vm.update.collectAsState()
    val transfer by vm.transfer.collectAsState()
    val interrupted by vm.interrupted.collectAsState()
    val outbox by vm.outbox.collectAsState()
    val cast by vm.cast.collectAsState()
    // The From PC badge: the outbox is listed once per connection (From PC refreshes it every 5 s itself).
    val connectedApi = (conn as? ConnectionState.Connected)?.api
    LaunchedEffect(connectedApi) { if (connectedApi != null) AppGraph.refreshOutbox() }
    val outboxCount = if (connectedApi != null) outbox?.files?.size ?: 0 else 0
    Box(Modifier.fillMaxSize()) {
        // "Send other files (documents, APKs…)" [A19]: the system chooser (SAF), any type, several files.
        val flow = rememberSendFlow(onStarted = { nav.push(Screen.Transfer) })
        // "Send photos & videos" (P3, P10): media permission flow first [A15], then the picker.
        val gate = rememberMediaGate(onReady = { nav.push(Screen.Picker(PickerMode.Send)) })
        var access by remember { mutableStateOf<MediaAccess?>(null) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = mediaAccess(ctx) }
        val chooseFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            AppGraph.log("send", "documents chosen: ${uris.size}")
            if (uris.isNotEmpty()) flow.send(SendRequest.Uris(uris, "documents"))
        }
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState())
                .padding(16.dp).testTag("home"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ferry", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                val pc = (conn as? ConnectionState.Connected)?.name
                if (pc != null) Text("  → $pc", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { nav.push(Screen.Settings) }) { Text(stringResource(R.string.btn_settings)) }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(conn)
                        Text(statusText(conn), Modifier.padding(start = 12.dp).testTag("status"), style = MaterialTheme.typography.bodyLarge)
                    }
                    if (conn !is ConnectionState.Connected && conn !is ConnectionState.Looking) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = vm::tryAgain) { Text(stringResource(R.string.try_again)) }
                            if (conn !is ConnectionState.OldPc) {
                                OutlinedButton(onClick = { nav.push(Screen.Settings) }) { Text(stringResource(R.string.enter_address)) }
                            }
                        }
                    }
                }
            }
            UpdateBanner(update)
            if (access == MediaAccess.Partial) PartialAccessBanner()
            transfer?.let { t ->
                // Ongoing (or last) transfer card → Transfer screen (design §4.1)
                val words = remember(ctx) { TransferWords(ctx.resources) }
                Card(Modifier.fillMaxWidth().clickable { nav.push(Screen.Transfer) }.testTag("home-transfer")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(words.headline(t), style = MaterialTheme.typography.titleMedium)
                        if (t.phase != Phase.Finished) LinearProgressIndicator(progress = { TransferMath.fraction(t) }, Modifier.fillMaxWidth())
                        Text(stringResource(R.string.transfer_card_open), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            cast?.takeIf { it.phase != CastPhase.Ended }?.let { c ->
                // A cast is running (Ph9): back to its Remote
                Card(Modifier.fillMaxWidth().clickable { nav.push(Screen.Remote) }.testTag("home-cast")) {
                    Text(stringResource(R.string.cast_now, c.tvName), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                }
            }
            if (interrupted > 0 && transfer == null) {
                // After a kill: the vivo steps, prominently [A8]
                Card(Modifier.fillMaxWidth().testTag("home-interrupted")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(LocalContext.current.resources.getQuantityString(R.plurals.interrupted, interrupted, interrupted), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.vivo_steps), style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { nav.push(Screen.Settings) }) { Text(stringResource(R.string.btn_settings)) }
                            TextButton(onClick = vm::dismissInterrupted) { Text(stringResource(R.string.ok)) }
                        }
                    }
                }
            }
            for (label in listOf(R.string.btn_send_photos, R.string.btn_send_files, R.string.btn_from_pc, R.string.btn_play_tv)) {
                Button(
                    onClick = {
                        when (label) {
                            R.string.btn_send_files -> chooseFiles.launch(arrayOf("*/*"))
                            R.string.btn_send_photos -> gate.open()
                            R.string.btn_from_pc -> nav.push(Screen.FromPc)
                            else -> nav.push(Screen.PlayOnTv) // Ph9
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                ) {
                    val text = if (label == R.string.btn_from_pc && outboxCount > 0) stringResource(R.string.btn_from_pc_count, outboxCount)
                    else stringResource(label)
                    Text(text, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        flow.Overlay()
        gate.Overlay()
    }
}
