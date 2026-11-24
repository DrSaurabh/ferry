package app.ferry.ui.phone

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import app.ferry.ui.common.TapLog
import app.ferry.ui.common.onClickLogged
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ferry.AppGraph
import app.ferry.BuildConfig
import app.ferry.R
import app.ferry.core.HostPort
import app.ferry.core.model.Peer
import app.ferry.net.ConnectionState
import app.ferry.ui.common.Battery
import app.ferry.ui.common.StatusDot
import app.ferry.ui.common.statusText
import app.ferry.ui.nav.Navigator
import kotlinx.coroutines.launch
import java.io.File

class SettingsViewModel : ViewModel() {
    private val prefs = AppGraph.prefs
    val connection = AppGraph.connection.state
    var address by mutableStateOf(prefs.manual?.toString() ?: "")
    var addressError by mutableStateOf(false)
    var found by mutableStateOf<List<Peer>?>(null) // null = not searched yet
    var searching by mutableStateOf(false)
    var slideshow by mutableIntStateOf(prefs.slideshowSeconds)

    /** Settings → "PC address" [B16]: validated with HostPort.parse; empty = forget the typed address. */
    fun saveAddress() {
        val t = address.trim()
        if (t.isEmpty()) {
            prefs.manual = null
            addressError = false
        } else {
            val hp = HostPort.parse(t)
            if (hp == null) {
                addressError = true
                return
            }
            prefs.manual = hp
            address = hp.toString()
            addressError = false
        }
        AppGraph.log("settings", "manual address: ${prefs.manual}")
        AppGraph.connection.refresh()
    }

    fun clearAddress() {
        address = ""
        saveAddress()
    }

    /** "Find again": lists every PC that answers (design §3.5 "Settings lists all found") and re-locates. */
    fun findAgain() {
        if (searching) return
        searching = true
        viewModelScope.launch {
            try {
                found = AppGraph.connection.discoverPcs()
            } finally {
                searching = false
            }
            AppGraph.connection.refresh()
        }
    }

    /** The user picked one of several PCs: remembered by name and address. */
    fun choose(p: Peer) {
        prefs.chosenName = p.name
        prefs.lastKnown = HostPort(p.host, p.port)
        prefs.manual = null
        address = ""
        AppGraph.log("settings", "chose ${p.name} at ${p.host}:${p.port}")
        AppGraph.connection.refresh()
    }

    fun setSlideshowSeconds(s: Int) {
        slideshow = s
        prefs.slideshowSeconds = s
    }
}

/** Settings (design §4.1.6, plan §4.3): PC, battery status [A8], slideshow default, version, diagnostics log. */
@Composable
fun SettingsScreen(nav: Navigator, vm: SettingsViewModel = viewModel()) {
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    val ctx = LocalContext.current
    val conn by vm.connection.collectAsState()
    var batteryOk by remember { mutableStateOf(Battery.isIgnoringOptimizations(ctx)) }
    var showSteps by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { batteryOk = Battery.isIgnoringOptimizations(ctx) }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState())
            .padding(16.dp).testTag("settings"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.btn_settings), style = MaterialTheme.typography.headlineMedium)

        Text(stringResource(R.string.settings_pc), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(conn)
            Text(statusText(conn), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(onClick = onClickLogged("settings.find_again", vm::findAgain), enabled = !vm.searching) {
            Text(stringResource(if (vm.searching) R.string.settings_searching else R.string.settings_find_again))
        }
        vm.found?.let { pcs ->
            if (pcs.isEmpty()) Text(stringResource(R.string.settings_none_found))
            val current = (conn as? ConnectionState.Connected)?.api?.base
            for (p in pcs) {
                val here = current != null && current.host == p.host && current.port == p.port
                TextButton(onClick = onClickLogged("settings.choose_pc") { vm.choose(p) }) { Text((if (here) "● " else "") + "${p.name}  ${p.host}:${p.port}") }
            }
        }
        OutlinedTextField(
            value = vm.address,
            onValueChange = {
                vm.address = it
                vm.addressError = false
            },
            label = { Text(stringResource(R.string.settings_address)) },
            isError = vm.addressError,
            supportingText = if (vm.addressError) ({ Text(stringResource(R.string.settings_address_bad)) }) else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.saveAddress() }),
            modifier = Modifier.fillMaxWidth().testTag("address-field"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onClickLogged("settings.save_address", vm::saveAddress)) { Text(stringResource(R.string.settings_save)) }
            TextButton(onClick = onClickLogged("settings.clear_address", vm::clearAddress)) { Text(stringResource(R.string.settings_clear)) }
        }

        HorizontalDivider()
        Text(stringResource(R.string.settings_battery), style = MaterialTheme.typography.titleMedium)
        if (batteryOk) {
            Text(stringResource(R.string.battery_ok))
        } else {
            TextButton(onClick = onClickLogged("settings.battery_fix") { Battery.request(ctx) }) { Text(stringResource(R.string.battery_fix)) }
        }
        TextButton(onClick = onClickLogged("settings.still_stopping") { showSteps = !showSteps }) { Text(stringResource(R.string.settings_still_stopping)) }
        if (showSteps) Text(stringResource(R.string.vivo_steps), style = MaterialTheme.typography.bodyMedium)

        HorizontalDivider()
        Text(stringResource(R.string.settings_slideshow), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in listOf(3, 5, 10)) {
                FilterChip(selected = vm.slideshow == s, onClick = onClickLogged("settings.slideshow_$s") { vm.setSlideshowSeconds(s) }, label = { Text("$s s") })
            }
        }

        HorizontalDivider()
        LoggingSettings()
        TextButton(onClick = onClickLogged("settings.share_log") { shareLog(ctx) }) { Text(stringResource(R.string.settings_share_log)) }
        Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)
    }
}

/** Settings → "Share diagnostics log" (design §9): the event log file(s) through FileProvider `logs/` [A14]. */
fun shareLog(ctx: Context) {
    val files = listOfNotNull(AppGraph.events.previousFile, AppGraph.events.file).filter { it.isFile && it.length() > 0 }
    if (files.isEmpty()) {
        Toast.makeText(ctx, R.string.settings_no_log, Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uris: List<Uri> = files.map { f: File -> FileProvider.getUriForFile(ctx, "app.ferry.files", f) }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_SUBJECT, "Ferry ${BuildConfig.VERSION_NAME} diagnostics log")
        val clip = ClipData.newRawUri("log", uris[0])
        for (u in uris.drop(1)) clip.addItem(ClipData.Item(u))
        send.clipData = clip
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (e: Exception) {
        AppGraph.log("settings", "share log failed: $e")
        Toast.makeText(ctx, e.toString(), Toast.LENGTH_LONG).show()
    }
}

/**
 * "Trial logging" (L2, H55: on = Detailed, the 2.1 default) and this phone's name on the PC (H76). The name is
 * saved when the field loses focus or Done is pressed; empty = the system's name again.
 */
@Composable
private fun LoggingSettings() {
    val prefs = AppGraph.prefs
    var detailed by remember { mutableStateOf(prefs.logDetailed) }
    var name by remember { mutableStateOf(prefs.deviceNameOverride ?: "") }
    val focus = LocalFocusManager.current
    fun saveName() {
        val n = name.trim()
        if (n != (prefs.deviceNameOverride ?: "")) {
            prefs.deviceNameOverride = n
            AppGraph.names.refresh()
        }
    }
    Text(stringResource(R.string.settings_logging), style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_trial_logging), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.settings_trial_logging_text), style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = detailed,
            onCheckedChange = { on -> // tap-id: settings.trial_logging_on / _off
                TapLog.tap(if (on) "settings.trial_logging_on" else "settings.trial_logging_off")
                detailed = on
                prefs.logDetailed = on
            },
            modifier = Modifier.testTag("trial-logging"),
        )
    }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(40) },
        label = { Text(stringResource(R.string.settings_device_name)) },
        placeholder = { Text(AppGraph.deviceName) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            saveName()
            focus.clearFocus()
        }),
        modifier = Modifier.fillMaxWidth().testTag("device-name-field").onFocusChanged { if (!it.isFocused) saveName() },
    )
}
