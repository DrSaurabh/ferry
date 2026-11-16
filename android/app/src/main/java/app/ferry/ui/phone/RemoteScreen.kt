package app.ferry.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ferry.AppGraph
import app.ferry.R
import app.ferry.cast.CastPhase
import app.ferry.cast.CastProblem
import app.ferry.cast.CastSession
import app.ferry.cast.CastStateMapper
import app.ferry.core.model.CastRequest
import app.ferry.core.model.ControlRequest
import app.ferry.ui.nav.Navigator

/**
 * The Remote (design §4.1.5, plan §4.3): title, position / duration (video) or "3 / 24" (photos), previous / play-pause / next / stop icons,
 * −10 s / +10 s for a video, 3 / 5 / 10 s per photo for a slideshow. While it is visible the TV is asked for its
 * state every second (CastService). Each button carries a fixed description (tests find it with UiAutomator).
 * Also opened by tapping the casting notification [A24].
 */
@Composable
fun RemoteScreen(nav: Navigator, vm: CastViewModel) {
    val session by vm.session.collectAsState()
    DisposableEffect(Unit) {
        AppGraph.casting.remoteVisible.incrementAndGet()
        onDispose { AppGraph.casting.remoteVisible.decrementAndGet() }
    }
    AskNotificationsOnce() // the casting notification carries Pause / Next / Stop [H23]
    BackHandler { nav.pop() }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState())
            .padding(16.dp).testTag("remote"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.remote_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { nav.pop() }) { Text(stringResource(R.string.back)) }
        }
        val s = session
        if (s == null) {
            Text(stringResource(R.string.remote_nothing), style = MaterialTheme.typography.bodyLarge)
        } else {
            RemoteBody(s, vm)
        }
    }
}

@Composable
private fun RemoteBody(s: CastSession, vm: CastViewModel) {
    val ctx = LocalContext.current
    val st = s.tvState
    val headline = when (s.phase) {
        CastPhase.Starting -> R.string.cast_starting
        CastPhase.Playing -> R.string.cast_playing_on
        CastPhase.Ended -> null
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (headline != null) Text(stringResource(headline, s.tvName), style = MaterialTheme.typography.bodyLarge)
            Text(
                st?.title ?: "",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("remote-item"),
            )
            val progress = st?.let { CastStateMapper.progress(it) } ?: ""
            val paused = if (st != null && s.phase == CastPhase.Playing && !st.playing) " · " + stringResource(R.string.cast_paused) else ""
            Text(progress + paused, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("remote-progress"))
        }
    }
    if (s.phase == CastPhase.Ended) {
        val why = when (s.problem) {
            null -> R.string.cast_ended
            CastProblem.CantServe -> R.string.cast_cant_serve
            CastProblem.CantReachTv -> R.string.cast_cant_reach_tv
            CastProblem.TvRefused -> R.string.cast_tv_refused
            CastProblem.StoppedOnTv -> R.string.cast_stopped_on_tv
            CastProblem.LostTv -> R.string.cast_lost_tv
        }
        Text(stringResource(why), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("remote-ended"))
    } else {
        RemoteControls(s, vm, ctx)
    }
}

@Composable
private fun RemoteControls(s: CastSession, vm: CastViewModel, ctx: android.content.Context) {
    val st = s.tvState
    val playing = st?.playing != false
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        RemoteIconButton(R.drawable.ic_prev, R.string.remote_prev, Modifier.weight(1f)) { vm.control(ControlRequest.PREV) }
        RemoteIconButton(if (playing) R.drawable.ic_pause else R.drawable.ic_play, R.string.remote_toggle, Modifier.weight(1f)) {
            vm.control(ControlRequest.TOGGLE)
        }
        RemoteIconButton(R.drawable.ic_next, R.string.remote_next, Modifier.weight(1f)) { vm.control(ControlRequest.NEXT) }
    }
    if (s.kind == CastRequest.VIDEO) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            RemoteButton("−10 s", R.string.remote_back10, Modifier.weight(1f), outlined = true) { vm.control(ControlRequest.SEEK, deltaMs = -10_000) }
            RemoteButton("+10 s", R.string.remote_fwd10, Modifier.weight(1f), outlined = true) { vm.control(ControlRequest.SEEK, deltaMs = 10_000) }
        }
    } else {
        Text(stringResource(R.string.remote_interval), style = MaterialTheme.typography.titleSmall)
        val now = CastStateMapper.intervalChoice(st?.intervalMs ?: 0)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (sec in CastStateMapper.INTERVALS_S) {
                val label = stringResource(R.string.remote_interval_s, sec)
                FilterChip(
                    selected = sec == now,
                    onClick = { vm.control(ControlRequest.INTERVAL, intervalMs = sec * 1000L) },
                    label = { Text(label) },
                    modifier = Modifier.semantics { contentDescription = label },
                )
            }
        }
    }
    Button(
        onClick = { vm.stop(ctx) },
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = ctx.getString(R.string.remote_stop) },
    ) {
        Icon(painterResource(R.drawable.ic_stop), contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.remote_stop), style = MaterialTheme.typography.titleMedium)
    }
}

/** ⏮ ⏯ ⏭ as drawn icons (Ph10): the ⏸ / ⏹ characters render as colour emoji on Android 14. */
@Composable
private fun RemoteIconButton(icon: Int, desc: Int, modifier: Modifier, onClick: () -> Unit) {
    val d = stringResource(desc)
    Button(onClick = onClick, modifier = modifier.heightIn(min = 64.dp).semantics { contentDescription = d }) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun RemoteButton(symbol: String, desc: Int, modifier: Modifier, outlined: Boolean = false, onClick: () -> Unit) {
    val d = stringResource(desc)
    val m = modifier.heightIn(min = 64.dp).semantics { contentDescription = d }
    if (outlined) {
        OutlinedButton(onClick = onClick, modifier = m) { Text(symbol, style = MaterialTheme.typography.titleMedium) }
    } else {
        Button(onClick = onClick, modifier = m) { Text(symbol, style = MaterialTheme.typography.headlineSmall) }
    }
}
