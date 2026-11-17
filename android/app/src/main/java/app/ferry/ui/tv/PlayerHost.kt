package app.ferry.ui.tv

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import app.ferry.AppGraph
import app.ferry.R
import app.ferry.player.CodecName
import app.ferry.player.FerryLoadErrorPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The TV video player's engine (design §5.2 [T2], plan §4.10): ExoPlayer over the **shared** Wi-Fi-bound OkHttp
 * client (`OkHttpDataSource`, connect 8 s / read 20 s [CP2] [B13], `X-Ferry-Device` [CP15]), [FerryLoadErrorPolicy]
 * (8 retries 1 s apart: the reconnect after the PC's 60 s idle drop, proven in Ph0b, H37) and a 15 s / 50 s buffer.
 * It also turns an unplayable video or sound into the plain-English line of [message] [A12]. Main thread only.
 */
@OptIn(UnstableApi::class)
class PlayerHost(ctx: Context, val url: String, val title: String) {
    private val res = ctx.applicationContext.resources

    /** The line to show over the video ("This TV can't play this video format (HEVC)"), or null. */
    val message = MutableStateFlow<String?>(null)

    /** The video is advancing right now (keep-screen-on follows it, audit O4). */
    val playing = MutableStateFlow(false)

    /**
     * Audit O15: a line that came from the track list only (not from a playback error). The player may still play a
     * track it calls "not supported" (one that merely exceeds what the decoder advertises, e.g. 4K on a 1080p stick),
     * so the "format" line goes away as soon as a video frame is drawn, and the "sound" line after [SOUND_NOTE_MS].
     */
    private var trackLine: String? = null
    private var frameShown = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Evidence for the tests and the log (T2: no `PlaybackException` across a long pause). */
    @Volatile var playbackError: PlaybackException? = null
        private set
    val loadErrors = AtomicInteger()
    val loadsStarted = AtomicInteger()

    val player: ExoPlayer = run {
        val client = AppGraph.httpClient().newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val sources = DefaultMediaSourceFactory(OkHttpDataSource.Factory(client)).setLoadErrorHandlingPolicy(FerryLoadErrorPolicy)
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 2_500, 5_000).build()
        ExoPlayer.Builder(ctx).setMediaSourceFactory(sources).setLoadControl(load)
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000) // the controller's icons match the D-pad's ±10 s
            .build()
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError = error
                message.value = errorLine(error)
                AppGraph.log("player", "error ${error.errorCodeName}: ${error.message} (cause ${error.cause}) → ${message.value}")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing.value = isPlaying
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (playbackError != null) return // an error line stays
                val video = unsupported(tracks, C.TRACK_TYPE_VIDEO)
                val audio = unsupported(tracks, C.TRACK_TYPE_AUDIO)
                val line = when {
                    video != null && !frameShown -> res.getString(R.string.cant_play_format, video)
                    audio != null -> res.getString(R.string.cant_play_sound, audio)
                    else -> null
                } ?: return
                trackLine = line
                message.value = line
                AppGraph.log("player", "tracks: $line")
                if (video == null || frameShown) main.postDelayed({ clearTrackLine(line, "shown for ${SOUND_NOTE_MS / 1000} s") }, SOUND_NOTE_MS)
            }

            override fun onRenderedFirstFrame() {
                frameShown = true
                trackLine?.let { clearTrackLine(it, "the video does play") }
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadStarted(t: AnalyticsListener.EventTime, l: LoadEventInfo, d: MediaLoadData) {
                loadsStarted.incrementAndGet()
            }

            override fun onLoadError(
                t: AnalyticsListener.EventTime, l: LoadEventInfo, d: MediaLoadData, e: IOException, cancelled: Boolean,
            ) {
                val n = loadErrors.incrementAndGet()
                AppGraph.log("player", "load error #$n (retrying): ${e.javaClass.simpleName}: ${e.message} (cause ${e.cause})")
            }
        })
    }

    /** Starts loading [url]; playback starts at [startMs] once [play] is called (or at once if [autoPlay]). */
    fun prepare(startMs: Long, autoPlay: Boolean) {
        player.setMediaItem(MediaItem.fromUri(url), startMs.coerceAtLeast(0))
        player.playWhenReady = autoPlay
        player.prepare()
        AppGraph.log("player", "open $title at ${startMs / 1000} s")
    }

    /** OK / Play-Pause. */
    fun toggle() {
        if (player.playWhenReady) player.pause() else player.play()
    }

    /** ±10 s (D-pad), ±30 s (Rewind / Fast-forward); stays inside the video. */
    fun seekBy(deltaMs: Long) {
        val dur = player.duration
        var to = player.currentPosition + deltaMs
        if (dur != C.TIME_UNSET && dur > 0) to = to.coerceAtMost(dur - 1_000)
        player.seekTo(to.coerceAtLeast(0))
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        player.release()
    }

    /** Takes a track-list line off the screen if it is still the one showing (never an error line). */
    private fun clearTrackLine(line: String, why: String) {
        if (trackLine == line && message.value == line && playbackError == null) {
            message.value = null
            trackLine = null
            AppGraph.log("player", "message cleared: $why")
        }
    }

    /** A track type that is present but has no playable track: its codec name, else null. */
    private fun unsupported(tracks: Tracks, type: Int): String? {
        val groups = tracks.groups.filter { it.type == type }
        if (groups.isEmpty() || groups.any { it.isSupported }) return null
        return codec(groups.first().getTrackFormat(0))
    }

    private companion object {
        const val SOUND_NOTE_MS = 8_000L
    }

    private fun codec(f: Format?): String = CodecName.of(f?.sampleMimeType, f?.codecs)

    private fun errorLine(e: PlaybackException): String {
        val formatProblem = e.errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
            e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        if (formatProblem) {
            val f = (e as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }?.rendererFormat
                ?: player.videoFormat
            return res.getString(R.string.cant_play_format, codec(f))
        }
        return res.getString(R.string.cant_play)
    }
}
