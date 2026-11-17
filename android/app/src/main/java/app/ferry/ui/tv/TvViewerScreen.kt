package app.ferry.ui.tv

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.ferry.AppGraph
import app.ferry.R
import app.ferry.core.model.ControlRequest
import app.ferry.ui.nav.Screen
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.size.Precision
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import okhttp3.Call
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

/** One photo of the viewer: [url] absolute, [key] = `name|size|mtime` (cache key). */
data class TvPhoto(val name: String, val url: String, val key: String)

/**
 * Photo viewer and slideshow (design §5.3 [T3], plan §4.10): full-screen photo decoded at screen size (Coil, EXIF
 * orientation honoured), 400 ms cross-fade, the next 2 photos preloaded. Slideshow: auto-advance every 3/5/10 s
 * (default 5; **Up / Down** on the remote change it and the TV remembers it, audit O8), looping; OK / Play-Pause pause and resume; Left/Right previous/next (that photo then gets
 * a full interval before the slideshow moves on, B76); overlay "3 / 24 · name" for 2 s after each key and photo
 * change. A photo that can't be decoded shows "Can't show this photo" and the slideshow moves on after 2 s [A20].
 * The viewer (OK on one photo) is the same screen, started paused at that photo. Keys through [TvKeys] (B74).
 *
 * A cast from the phone (Ph9, design §5.5) uses the same screen with the phone's [intervalMs] and the phone's
 * [commands] (toggle / play / pause / next / prev / interval); the remote's keys keep working too [T5].
 */
@Composable
fun TvViewerScreen(
    s: Screen.TvViewer,
    onBack: () -> Unit,
    intervalMs: Long? = null,
    commands: Flow<ControlRequest>? = null,
) {
    val ctx = LocalContext.current
    val photos = s.photos
    val n = photos.size
    var index by remember(s) { mutableIntStateOf(s.start.coerceIn(0, (n - 1).coerceAtLeast(0))) }
    var playing by remember(s) { mutableStateOf(s.slideshow) }
    var restart by remember(s) { mutableIntStateOf(0) } // a key press restarts the timer of the current photo
    var overlay by remember(s) { mutableIntStateOf(0) } // bumped → the overlay shows for 2 s
    var overlayShown by remember { mutableStateOf(false) }
    var intervalShown by remember(s) { mutableStateOf(false) } // the overlay also names the interval (after Up / Down)
    val failed = remember(s) { mutableStateMapOf<Int, Boolean>() }
    var interval by remember(s) { mutableLongStateOf(intervalMs ?: (AppGraph.prefs.slideshowSeconds.coerceIn(1, 60) * 1000L)) }
    val dm = ctx.resources.displayMetrics
    val (w, h) = dm.widthPixels to dm.heightPixels
    val loader = remember { TvViewerImages.loader(ctx) }

    fun go(to: Int, how: String) {
        if (n == 0) return
        index = ((to % n) + n) % n
        TvViewerState.moved(index, photos[index].name, how)
    }

    DisposableEffect(s) {
        TvViewerState.open(n, s.slideshow)
        if (n > 0) TvViewerState.moved(index, photos[index].name, "open")
        AppGraph.log("tv", "viewer: $n photos from ${s.start}, slideshow=${s.slideshow}, ${interval / 1000} s")
        onDispose { TvViewerState.close() }
    }
    LaunchedEffect(playing) { TvViewerState.playing = playing }
    LaunchedEffect(interval) { TvViewerState.intervalMs = interval }
    // The phone's Remote (cast): the same actions as the remote's keys.
    LaunchedEffect(s, commands) {
        commands?.collect { c ->
            when (c.action) {
                ControlRequest.TOGGLE -> { playing = !playing; overlay++ }
                ControlRequest.PLAY -> { playing = true; overlay++ }
                ControlRequest.PAUSE -> { playing = false; overlay++ }
                ControlRequest.NEXT -> { go(index + 1, "phone"); restart++ }
                ControlRequest.PREV -> { go(index - 1, "phone"); restart++ }
                ControlRequest.INTERVAL -> c.intervalMs?.takeIf { it > 0 }?.let { interval = it; restart++; overlay++ }
                else -> {}
            }
            Unit
        }
    }
    // Auto-advance; a photo that failed moves on after the message's 2 s [A20].
    LaunchedEffect(index, playing, restart, failed[index], interval) {
        if (!playing || n < 2) return@LaunchedEffect
        if (failed[index] == true) {
            delay(2_000)
            go(index + 1, "skip")
        } else {
            delay(interval)
            go(index + 1, "auto")
        }
    }
    // Preload the next 2 at screen size (same memory key as the shown photo, so the switch is instant).
    LaunchedEffect(index) {
        for (k in 1..2) if (n > k) loader.enqueue(TvViewerImages.request(ctx, photos[(index + k) % n], w, h))
    }
    LaunchedEffect(index) { overlay++ }
    LaunchedEffect(overlay) {
        overlayShown = true
        TvViewerState.overlayVisible = true
        delay(2_000)
        overlayShown = false
        intervalShown = false
        TvViewerState.overlayVisible = false
    }
    KeepScreenOn.Pinned(active = playing && n > 1) // paused or a single photo: the 30-minute rule (audit O4)
    BackHandler { onBack() }

    /** Up / Down in a slideshow: 3 → 5 → 10 s per photo (audit O8, H6); the TV remembers it for its own slideshows. */
    fun stepInterval(up: Boolean) {
        val next = SlideshowIntervals.step((interval / 1000).toInt(), up)
        interval = next * 1000L
        if (intervalMs == null) AppGraph.prefs.slideshowSeconds = next // a cast keeps the phone's default apart
        intervalShown = true
        restart++
        overlay++
        AppGraph.log("tv", "slideshow: $next s per photo")
    }
    TvKeys.Handle { e ->
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0
        val code = e.keyCode
        when {
            TvKeys.isOk(code) || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (first) { playing = !playing; overlay++ }; true }
            code == KeyEvent.KEYCODE_MEDIA_PLAY -> { if (first) { playing = true; overlay++ }; true }
            code == KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (first) { playing = false; overlay++ }; true }
            code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || code == KeyEvent.KEYCODE_MEDIA_NEXT ->
                { if (down) { go(index + 1, "key"); restart++ }; true }
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_MEDIA_REWIND || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS ->
                { if (down) { go(index - 1, "key"); restart++ }; true }
            code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (first) { if (s.slideshow) stepInterval(up = code == KeyEvent.KEYCODE_DPAD_UP) else overlay++ }
                true
            }
            else -> false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("tv-viewer")) {
        if (n > 0) {
            Crossfade(targetState = index, animationSpec = tween(400), label = "photo") { i ->
                Box(Modifier.fillMaxSize()) {
                    if (failed[i] == true) {
                        val line = stringResource(if (heifUnsupported(photos[i].name)) R.string.cant_show_format else R.string.cant_show_photo)
                        Text(
                            line,
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center).testTag("tv-viewer-message"),
                        )
                        DisposableEffect(i) {
                            TvViewerState.failedShown += photos[i].name
                            TvViewerState.messageVisible = true
                            onDispose { TvViewerState.messageVisible = false }
                        }
                    } else {
                        AsyncImage(
                            model = TvViewerImages.request(ctx, photos[i], w, h),
                            contentDescription = photos[i].name,
                            imageLoader = loader,
                            contentScale = ContentScale.Fit,
                            onSuccess = { TvViewerState.shown += photos[i].name },
                            onError = {
                                AppGraph.log("tv", "can't show ${photos[i].name}: ${it.result.throwable}")
                                failed[i] = true
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        if (overlayShown && n > 0) {
            val paused = if (playing) "" else "  ·  " + stringResource(R.string.cast_paused) // text, not ⏸ (emoji on some fonts)
            val every = if (intervalShown) "  ·  " + stringResource(R.string.tv_slideshow_every, (interval / 1000).toInt()) else ""
            Text(
                "${index + 1} / $n  ·  ${photos[index].name}$paused$every",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 48.dp, vertical = 27.dp)
                    .background(Color(0x99000000)).padding(10.dp).testTag("tv-viewer-overlay"),
            )
        }
    }
}

/** HEIC/HEIF is decoded by the platform only from Android 9 (design §5.3). */
private fun heifUnsupported(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return (ext == "heic" || ext == "heif") && Build.VERSION.SDK_INT < 28
}

/**
 * The viewer's image loader (plan §4.10): built like the thumbnails' one ([TvThumbnails]) — the shared OkHttp client
 * looked up per call [PR19] — with a memory cache only: originals are 5–15 MB and a Fire TV Stick has little storage.
 */
object TvViewerImages {
    @Volatile private var loader: ImageLoader? = null

    fun loader(ctx: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(ctx.applicationContext)
            .callFactory(Call.Factory { request -> AppGraph.httpClient().newCall(request) })
            .memoryCache { MemoryCache.Builder(ctx.applicationContext).maxSizePercent(0.25).build() }
            .diskCache(null)
            .respectCacheHeaders(false)
            .build()
            .also { loader = it }
    }

    fun request(ctx: Context, p: TvPhoto, w: Int, h: Int): ImageRequest = ImageRequest.Builder(ctx)
        .data(p.url)
        .size(w, h)
        .precision(Precision.INEXACT)
        .memoryCacheKey("${p.key}|${w}x$h")
        .build()
}

/** The slideshow's seconds per photo (H6): 3 / 5 / 10; Up = next longer, Down = next shorter, no wrap-around. */
object SlideshowIntervals {
    val CHOICES = listOf(3, 5, 10)

    fun step(currentSeconds: Int, up: Boolean): Int =
        if (up) CHOICES.firstOrNull { it > currentSeconds } ?: CHOICES.last()
        else CHOICES.lastOrNull { it < currentSeconds } ?: CHOICES.first()
}

/**
 * The viewer on screen (B69: app state, not a Compose test rule). **Not only for tests** (audit O11): a cast's
 * state for the phone is read from [open], [index], [name], [playing] and [intervalMs] (`TvCastScreen`). [moves],
 * [shown], [failedShown] and the two `…Visible` flags are test evidence; [moves] keeps the last [MAX_MOVES] only.
 */
object TvViewerState {
    const val MAX_MOVES = 200

    class Move(val index: Int, val name: String, val how: String, val atMs: Long) {
        override fun toString() = "$how→$index($name)@$atMs"
    }

    @Volatile var open = false
    @Volatile var count = 0
    @Volatile var slideshow = false
    @Volatile var playing = false
    @Volatile var intervalMs = 0L
    @Volatile var index = -1
    @Volatile var name: String? = null
    @Volatile var overlayVisible = false
    @Volatile var messageVisible = false
    val moves = CopyOnWriteArrayList<Move>()
    val shown = CopyOnWriteArraySet<String>()
    val failedShown = CopyOnWriteArraySet<String>()

    fun open(n: Int, slideshow: Boolean) {
        open = true
        count = n
        this.slideshow = slideshow
        playing = slideshow
        moves.clear()
    }

    fun moved(i: Int, name: String, how: String) {
        index = i
        this.name = name
        moves += Move(i, name, how, SystemClock.uptimeMillis())
        while (moves.size > MAX_MOVES) moves.removeAt(0) // a slideshow left running must not grow this for ever (O11)
    }

    /**
     * [shown]/[failedShown] are emptied here, not in [open]: a photo already in the memory cache reports success while
     * the screen is first composed, before the screen's own effect calls [open].
     */
    fun close() {
        shown.clear()
        failedShown.clear()
        open = false
        overlayVisible = false
        messageVisible = false
    }
}
