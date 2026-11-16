package app.ferry.ui.tv

import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.ferry.AppGraph
import app.ferry.player.FerryLoadErrorPolicy
import java.util.concurrent.TimeUnit

/**
 * **Interim** TV player for Ph8a (plan §6 Ph8a: "its Player step uses the Ph0b probe player until Ph8b"): the
 * Ph0b probe's stack (ExoPlayer + `OkHttpDataSource` over the shared client, connect 8 s / read 20 s,
 * [FerryLoadErrorPolicy], the §4.10 buffer sizes) on a plain `SurfaceView`; OK toggles play/pause, Back returns to
 * Home (where focus memory puts the focus back on this card). Ph8b replaces it with `TvPlayerScreen` + `PlayerHost`
 * (controller, seek keys, resume, format messages).
 */
@OptIn(UnstableApi::class)
@Composable
fun TvPlayerScreen(name: String, url: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val player = remember(url) {
        val client = AppGraph.httpClient().newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        val sources = DefaultMediaSourceFactory(OkHttpDataSource.Factory(client)).setLoadErrorHandlingPolicy(FerryLoadErrorPolicy)
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 2_500, 5_000).build()
        ExoPlayer.Builder(ctx).setMediaSourceFactory(sources).setLoadControl(load).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) {
        TvPlayerState.player = player
        AppGraph.log("tv", "player: $name")
        onDispose {
            TvPlayerState.player = null
            player.release()
        }
    }
    KeepScreenOn.Pinned()
    BackHandler { onBack() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("tv-player")
            .focusRequester(focus)
            .onPreviewKeyEvent {
                val ok = it.key == Key.DirectionCenter || it.key == Key.Enter || it.key == Key.MediaPlayPause
                if (ok && it.type == KeyEventType.KeyUp) {
                    if (player.isPlaying) player.pause() else player.play()
                }
                ok
            }
            .focusable(),
    ) {
        AndroidView(factory = { c -> SurfaceView(c).also { player.setVideoSurfaceView(it) } }, modifier = Modifier.fillMaxSize())
        Text(name, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
    }
}

/** The interim player, for tests (main thread only). */
object TvPlayerState {
    @Volatile var player: ExoPlayer? = null
}
