package app.ferry.ui.tv

import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.ferry.AppGraph
import app.ferry.R
import app.ferry.cast.CastStateMapper
import app.ferry.cast.Reachability
import app.ferry.core.model.CastRequest
import app.ferry.core.model.ControlRequest
import app.ferry.ui.nav.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A cast from the phone on the TV (design §5.5, plan §4.10): the same player and slideshow screens as for the PC's
 * files, fed with the phone's URLs. The phone's controls arrive through [CastSinkImpl.commands]; the remote's keys
 * work as usual [T5]; Back ends the cast. The state the phone polls is refreshed every 500 ms. If the phone's file
 * server stops answering for 10 s: "Lost connection to the phone" and back to Home.
 */
@Composable
fun TvCastScreen(show: CastSinkImpl.Show) {
    when (show.req.kind) {
        CastRequest.VIDEO -> CastVideo(show)
        else -> CastPhotos(show)
    }
    LaunchedEffect(show.id) { watchPhone(show) }
}

@OptIn(UnstableApi::class)
@Composable
private fun CastVideo(show: CastSinkImpl.Show) {
    val items = show.req.items
    var index by remember(show.id) { mutableIntStateOf(show.req.start.coerceIn(0, items.size - 1)) }
    val item = items[index]
    key(show.id, index) {
        val screen = remember { Screen.TvPlayer(item.name, item.url, resumeKey = null) } // no resume for casts
        TvPlayerScreen(screen) { CastSinkImpl.stop("Back on the TV") }
    }
    LaunchedEffect(show.id) {
        CastSinkImpl.commands.collect { c ->
            val h = TvPlayerState.host
            when (c.action) {
                ControlRequest.TOGGLE -> h?.toggle()
                ControlRequest.PLAY -> h?.player?.play()
                ControlRequest.PAUSE -> h?.player?.pause()
                ControlRequest.SEEK -> h?.seekBy(c.deltaMs ?: 0L)
                ControlRequest.NEXT -> if (index < items.size - 1) index++
                ControlRequest.PREV -> if (h != null && h.player.currentPosition > 3_000) h.player.seekTo(0) else if (index > 0) index--
                else -> {}
            }
            Unit
        }
    }
    LaunchedEffect(show.id) {
        while (true) {
            val h = TvPlayerState.host
            if (h != null) {
                val p = h.player
                val ended = p.playbackState == Player.STATE_ENDED
                if (ended && index < items.size - 1) index++ // several videos: the next one starts by itself
                val dur = p.duration.let { if (it == C.TIME_UNSET) -1L else it }
                CastSinkImpl.report(
                    show.id,
                    CastStateMapper.video(index, items.size, items[index].name, p.playWhenReady, ended, h.playbackError != null, p.currentPosition, dur),
                )
            }
            delay(500)
        }
    }
}

@Composable
private fun CastPhotos(show: CastSinkImpl.Show) {
    val photos = remember(show.id) { show.req.items.map { TvPhoto(it.name, it.url, key = it.url) } }
    val screen = remember(show.id) { Screen.TvViewer(photos, show.req.start, slideshow = true) }
    val commands = remember(show.id) { CastSinkImpl.commands.filter { it.action != ControlRequest.STOP } }
    TvViewerScreen(screen, onBack = { CastSinkImpl.stop("Back on the TV") }, intervalMs = show.req.intervalMs, commands = commands)
    LaunchedEffect(show.id) {
        while (true) {
            if (TvViewerState.open && TvViewerState.index >= 0) {
                CastSinkImpl.report(
                    show.id,
                    CastStateMapper.photos(TvViewerState.index, photos.size, TvViewerState.name, TvViewerState.playing, TvViewerState.intervalMs),
                )
            }
            delay(500)
        }
    }
}

/** Every 2 s a HEAD of the item on screen; nothing for 10 s → "Lost connection to the phone", Home (design §5.5). */
private suspend fun watchPhone(show: CastSinkImpl.Show) {
    val client = AppGraph.httpClient().newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
    val watch = Reachability(SystemClock.elapsedRealtime(), LOST_MS)
    while (true) {
        delay(2_000)
        val i = CastSinkImpl.snapshot.index.coerceIn(0, show.req.items.size - 1)
        val url = show.req.items[i].url
        val ok = withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url(url).head().build()).execute().use { it.isSuccessful }
            } catch (e: IOException) {
                false
            } catch (e: IllegalArgumentException) { // not a valid URL (refused by the receiver already)
                false
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (ok) {
            watch.ok(now)
        } else if (watch.failed(now)) {
            AppGraph.log("cast", "the phone's file server has not answered for ${LOST_MS / 1000} s")
            Toast.makeText(AppGraph.app, R.string.lost_phone, Toast.LENGTH_LONG).show()
            CastSinkImpl.stop("lost the phone")
            return
        }
    }
}

private const val LOST_MS = 10_000L
