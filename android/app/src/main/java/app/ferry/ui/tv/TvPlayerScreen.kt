package app.ferry.ui.tv

import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import app.ferry.AppGraph
import app.ferry.R
import app.ferry.player.ResumeRule
import app.ferry.ui.common.requestFocusSoon
import app.ferry.ui.common.tvFocusRing
import app.ferry.ui.nav.Screen
import kotlinx.coroutines.delay

/**
 * The TV video player (design §5.2 [T2], plan §4.10): [PlayerHost] (shared OkHttp client, retry policy, buffers)
 * shown in a media3 `PlayerView` whose controller is **display-only** (never focusable, descendants blocked) [A12].
 * The remote's keys come through [TvKeys] (B74): OK / Play-Pause toggle, Play, Pause; Left/Right −10/+10 s;
 * Rewind / Fast-forward −30/+30 s (held keys repeat); Up (and every handled key) shows the controller; Back saves
 * the position and returns to Home. Resume [A12]: the position is saved every 5 s and on leaving; a video opened
 * again more than 30 s in and more than 30 s before its end first asks "Resume from 12:34" / "Start over", with
 * the focus on Resume so one OK continues.
 */
@OptIn(UnstableApi::class)
@Composable
fun TvPlayerScreen(s: Screen.TvPlayer, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val host = remember(s) { PlayerHost(ctx, s.url, s.name) }
    val offer = remember(s) {
        s.resumeKey?.let { AppGraph.resume.get(it) }?.takeIf { ResumeRule.offer(it.posMs, it.durMs) }
    }
    var asking by remember(s) { mutableStateOf(offer != null) }
    var view by remember { mutableStateOf<PlayerView?>(null) }
    var controls by remember { mutableStateOf(false) }
    val message by host.message.collectAsState()

    fun savePosition() {
        val key = s.resumeKey ?: return
        if (asking) return // nothing watched yet: keep the saved position
        val p = host.player
        if (p.playbackState == Player.STATE_ENDED) {
            AppGraph.resume.remove(key)
        } else if (p.playbackState != Player.STATE_IDLE || p.currentPosition > 0) {
            AppGraph.resume.save(key, p.currentPosition, p.duration.takeIf { it != C.TIME_UNSET } ?: -1)
        }
    }

    DisposableEffect(host) {
        // With a resume question the video is loaded at the saved position but waits for the answer.
        host.prepare(offer?.posMs ?: 0, autoPlay = offer == null)
        TvPlayerState.host = host
        TvPlayerState.resumeOffer = offer?.posMs
        onDispose {
            savePosition()
            AppGraph.log("player", "closed ${s.name} at ${host.player.currentPosition / 1000} s")
            TvPlayerState.host = null
            TvPlayerState.resumeOffer = null
            TvPlayerState.resumeFocused = false
            host.release()
        }
    }
    LaunchedEffect(host) {
        while (true) {
            delay(5_000)
            if (host.player.isPlaying) savePosition()
        }
    }
    KeepScreenOn.Pinned()
    BackHandler { onBack() }

    fun show() {
        view?.showController()
    }
    TvKeys.Handle { e ->
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0
        val code = e.keyCode
        when {
            TvKeys.isOk(code) || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (first) { host.toggle(); show() }; true }
            code == KeyEvent.KEYCODE_MEDIA_PLAY -> { if (first) { host.player.play(); show() }; true }
            code == KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (first) { host.player.pause(); show() }; true }
            code == KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) { host.seekBy(-10_000); show() }; true }
            code == KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) { host.seekBy(10_000); show() }; true }
            code == KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) { host.seekBy(-30_000); show() }; true }
            code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) { host.seekBy(30_000); show() }; true }
            code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) show(); true }
            else -> false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("tv-player")) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    player = host.player
                    useController = true
                    controllerAutoShow = true
                    controllerShowTimeoutMs = 3_000
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowSubtitleButton(false)
                    // Display-only controller [A12]: the screen handles the keys; nothing in here takes focus.
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                        controls = v == android.view.View.VISIBLE
                        TvPlayerState.controllerVisible = controls
                    })
                    view = this
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        if (controls) {
            Text(
                s.name,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 48.dp, vertical = 27.dp)
                    .background(Color(0x99000000)).padding(8.dp),
            )
        }
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).background(Color(0xCC000000)).padding(24.dp).testTag("tv-player-message"),
            )
        }
    }

    if (asking && offer != null) {
        ResumeDialog(
            posMs = offer.posMs,
            onResume = {
                asking = false
                AppGraph.log("player", "resume from ${offer.posMs / 1000} s")
                host.player.play()
            },
            onStartOver = {
                asking = false
                AppGraph.log("player", "start over")
                host.player.seekTo(0)
                host.player.play()
            },
            onDismiss = onBack,
        )
    }
}

/** "Resume from 12:34" (focused first) / "Start over" (design §5.2 [A12]). Back leaves the player. */
@Composable
private fun ResumeDialog(posMs: Long, onResume: () -> Unit, onStartOver: () -> Unit, onDismiss: () -> Unit) {
    val resume = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusSoon(resume) { TvPlayerState.resumeFocused } }
    val pill = RoundedCornerShape(50)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = onResume,
                modifier = Modifier.tvFocusRing(pill) { TvPlayerState.resumeFocused = it }.focusRequester(resume).testTag("tv-resume"),
            ) { Text(stringResource(R.string.resume_from, ResumeRule.clock(posMs))) }
        },
        dismissButton = {
            OutlinedButton(onClick = onStartOver, modifier = Modifier.tvFocusRing(pill).testTag("tv-start-over")) {
                Text(stringResource(R.string.start_over))
            }
        },
        modifier = Modifier.testTag("tv-resume-dialog"),
    )
}

/** The player on screen, for tests (main thread for [player] calls). */
object TvPlayerState {
    @Volatile var host: PlayerHost? = null
    val player get() = host?.player

    /** The saved position the resume question offers (null = no question). */
    @Volatile var resumeOffer: Long? = null
    @Volatile var resumeFocused = false
    @Volatile var controllerVisible = false
}
