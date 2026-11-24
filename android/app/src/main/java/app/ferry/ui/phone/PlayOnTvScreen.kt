package app.ferry.ui.phone

import app.ferry.ui.common.PhoneTopBar
import app.ferry.ui.common.onClickLogged
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.ferry.R
import app.ferry.net.ConnectionManager
import app.ferry.net.RetrySchedule
import kotlinx.coroutines.delay
import app.ferry.cast.CastPhase
import app.ferry.media.PickerMode
import app.ferry.ui.nav.Navigator
import app.ferry.ui.nav.Screen

/**
 * "Enter the TV's address" (audit O2): shown when no TV was found. The TV's home screen shows its address
 * ("This TV: … · address 192.168.1.30"); number keyboard, prefilled with the phone's own first three numbers.
 */
@Composable
private fun TvAddressEntry(vm: CastViewModel) {
    if (!vm.addressEditing) {
        TextButton(onClick = onClickLogged("play_on_tv.enter_address") { vm.beginAddress() }, modifier = Modifier.testTag("cast-enter-address")) {
            Text(stringResource(R.string.cast_enter_address))
        }
        return
    }
    Text(stringResource(R.string.cast_address_hint), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = vm.address,
        onValueChange = {
            vm.address = it
            vm.addressProblem = null
        },
        isError = vm.addressProblem != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { vm.tryAddress() }),
        modifier = Modifier.fillMaxWidth().testTag("cast-address-field"),
    )
    vm.addressProblem?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("cast-address-problem")) }
    Button(onClick = onClickLogged("play_on_tv.use_address") { vm.tryAddress() }, modifier = Modifier.testTag("cast-use-address")) { Text(stringResource(R.string.cast_use_address)) }
}

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
    LaunchedEffect(Unit) { vm.find() }
    // M19: no TV found → look again quietly, 10 s for a minute, then every 60 s; fast again on resume.
    val retry = remember { RetrySchedule(SystemClock.elapsedRealtime()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { retry.reset(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(ConnectionManager.RETRY_TICK_MS)
            val now = SystemClock.elapsedRealtime()
            if (vm.finding.value || vm.chosen.value != null || vm.found.value.isNotEmpty()) retry.reset(now)
            else if (retry.due(now)) vm.retryQuietly(retry.fired(now))
        }
    }
    BackHandler { nav.pop() }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState())
                .padding(16.dp).testTag("play-on-tv"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PhoneTopBar(stringResource(R.string.btn_play_tv), showBack = true)
            val s = session
            if (s != null && s.phase != CastPhase.Ended) {
                Card(Modifier.fillMaxWidth().clickable(onClick = onClickLogged("play_on_tv.cast_now") { nav.push(Screen.Remote) }).testTag("cast-now")) {
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
                            TextButton(onClick = onClickLogged("play_on_tv.find_again") { vm.find() }) { Text(stringResource(R.string.cast_find_again)) }
                        }
                        found.size > 1 -> {
                            Text(stringResource(R.string.cast_choose_tv), style = MaterialTheme.typography.titleMedium)
                            for (t in found) {
                                OutlinedButton(onClick = onClickLogged("play_on_tv.choose_tv") { vm.choose(t) }, Modifier.fillMaxWidth()) { Text("${t.name} (${t.hp.host})") }
                            }
                        }
                        else -> {
                            Text(stringResource(R.string.tv_not_found), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("cast-no-tv"))
                            OutlinedButton(onClick = onClickLogged("play_on_tv.find_again") { vm.find() }) { Text(stringResource(R.string.cast_find_again)) } // the text says "tap Find again"
                            TvAddressEntry(vm)
                        }
                    }
                }
            }
            if (chosen != null) {
                for ((mode, label) in listOf(PickerMode.CastVideo to R.string.cast_videos, PickerMode.CastPhotos to R.string.cast_photos)) {
                    Button(
                        onClick = onClickLogged(if (mode == PickerMode.CastVideo) "play_on_tv.videos" else "play_on_tv.photos") {
                            nav.push(Screen.Picker(mode)) // the picker shows the media banner with Fix if needed (U2)
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    ) { Text(stringResource(label), style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }
}
