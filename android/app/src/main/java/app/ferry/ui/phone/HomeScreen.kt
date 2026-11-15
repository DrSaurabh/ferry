package app.ferry.ui.phone

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ferry.AppGraph
import app.ferry.BuildConfig
import app.ferry.R
import app.ferry.net.ConnectionState
import app.ferry.ui.common.StatusDot
import app.ferry.ui.common.UpdateBanner
import app.ferry.ui.common.comingSoon
import app.ferry.ui.common.statusText
import app.ferry.ui.nav.Navigator
import app.ferry.ui.nav.Screen

class HomeViewModel : ViewModel() {
    val connection = AppGraph.connection.state
    val update = AppGraph.update
    fun tryAgain() = AppGraph.connection.refresh()
}

/**
 * Home (design §4.1, plan §4.3): "→ PC" + status, the four big buttons, banners. In Ph4 the buttons of later
 * phases are placeholders ([comingSoon]; disabled on release builds, which are not given to the user).
 */
@Composable
fun HomeScreen(nav: Navigator, vm: HomeViewModel = viewModel()) {
    val ctx = LocalContext.current
    val conn by vm.connection.collectAsState()
    val update by vm.update.collectAsState()
    val later = BuildConfig.DEBUG
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
        UpdateBanner(update, onClick = { comingSoon(ctx) })
        for (label in listOf(R.string.btn_send_photos, R.string.btn_send_files, R.string.btn_from_pc, R.string.btn_play_tv)) {
            Button(
                onClick = { comingSoon(ctx) },
                enabled = later,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            ) { Text(stringResource(label), style = MaterialTheme.typography.titleMedium) }
        }
    }
}
