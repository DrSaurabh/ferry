package app.ferry.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.ferry.R
import app.ferry.cast.CastPhase
import app.ferry.media.PickerMode
import app.ferry.ui.common.rememberMediaGate
import app.ferry.ui.nav.Navigator
import app.ferry.ui.nav.Screen

/**
 * Play on TV (P9; design §4.1.5, plan §4.3): finds the TV (remembered one first [A24]), or lists several to choose
 * from, or says "On the TV, open Ferry and leave it on its home screen" [A10]; then **Videos** (picker: one video) or
 * **Photo slideshow** (picker: photos), behind the same media permission flow as sending [A15].
 */
@Composable
fun PlayOnTvScreen(nav: Navigator, vm: CastViewModel) {
    val finding by vm.finding.collectAsState()
    val found by vm.found.collectAsState()
    val chosen by vm.chosen.collectAsState()
    val session by vm.session.collectAsState()
    var target by remember { mutableStateOf(PickerMode.CastVideo) }
    val gate = rememberMediaGate(onReady = { nav.push(Screen.Picker(target)) })
    LaunchedEffect(Unit) { vm.find() }
    BackHandler { nav.pop() }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState())
                .padding(16.dp).testTag("play-on-tv"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.btn_play_tv), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { nav.pop() }) { Text(stringResource(R.string.back)) }
            }
            val s = session
            if (s != null && s.phase != CastPhase.Ended) {
                Card(Modifier.fillMaxWidth().clickable { nav.push(Screen.Remote) }.testTag("cast-now")) {
                    Text(stringResource(R.string.cast_now, s.tvName), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val c = chosen
                    when {
                        finding -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.cast_finding), Modifier.padding(start = 12.dp).testTag("cast-finding"))
                        }
                        c != null -> {
                            Text(stringResource(R.string.cast_tv, c.name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("cast-tv"))
                            TextButton(onClick = { vm.find() }) { Text(stringResource(R.string.cast_find_again)) }
                        }
                        found.size > 1 -> {
                            Text(stringResource(R.string.cast_choose_tv), style = MaterialTheme.typography.titleMedium)
                            for (t in found) {
                                OutlinedButton(onClick = { vm.choose(t) }, Modifier.fillMaxWidth()) { Text("${t.name} (${t.hp.host})") }
                            }
                        }
                        else -> {
                            Text(stringResource(R.string.tv_not_found), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("cast-no-tv"))
                            OutlinedButton(onClick = { vm.find() }) { Text(stringResource(R.string.cast_find_again)) } // the text says "tap Find again"
                        }
                    }
                }
            }
            if (chosen != null) {
                for ((mode, label) in listOf(PickerMode.CastVideo to R.string.cast_videos, PickerMode.CastPhotos to R.string.cast_photos)) {
                    Button(
                        onClick = {
                            target = mode
                            gate.open()
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    ) { Text(stringResource(label), style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
        gate.Overlay()
    }
}
