package app.ferry.cast

import app.ferry.core.HostPort
import app.ferry.core.model.CastRequest
import app.ferry.core.model.CastState
import app.ferry.player.ResumeRule

/**
 * Pure rules of casting (design §5.5, §6; plan §4.13 `CastStateMapperTest`): what the TV reports as its
 * `GET /api/v1/state`, what the phone's Remote shows, and when either side gives up on the other.
 */
object CastStateMapper {
    val IDLE = CastState()

    /** The Remote's interval choices for a slideshow (design §4.1.5). */
    val INTERVALS_S = listOf(3, 5, 10)

    /**
     * TV, video: the player's state. [durationMs] ≤ 0 = not known yet (ExoPlayer's TIME_UNSET is negative);
     * "playing" = the player wants to play and is neither at the end nor failed (buffering counts as playing).
     */
    fun video(
        index: Int, count: Int, title: String?, playWhenReady: Boolean, ended: Boolean, failed: Boolean,
        positionMs: Long, durationMs: Long,
    ): CastState {
        val dur = if (durationMs > 0) durationMs else 0L
        var pos = positionMs.coerceAtLeast(0L)
        if (dur > 0) pos = pos.coerceAtMost(dur)
        return CastState(
            kind = CastRequest.VIDEO, index = index, count = count, title = title,
            playing = playWhenReady && !ended && !failed, positionMs = pos, durationMs = dur,
        )
    }

    /** TV, slideshow: the photo on screen and whether it advances by itself. */
    fun photos(index: Int, count: Int, title: String?, playing: Boolean, intervalMs: Long): CastState =
        CastState(kind = CastRequest.PHOTOS, index = index, count = count, title = title, playing = playing, intervalMs = intervalMs)

    /** TV: the state right after a cast arrived, before its screen reports (so the phone never sees "idle" then). */
    fun starting(req: CastRequest): CastState {
        val i = req.start.coerceIn(0, (req.items.size - 1).coerceAtLeast(0))
        return CastState(
            kind = req.kind, index = i, count = req.items.size, title = req.items.getOrNull(i)?.name, playing = true,
            intervalMs = if (req.kind == CastRequest.PHOTOS) req.intervalMs else 0,
        )
    }

    /** Phone (Remote): "1:23 / 5:00" for a video, "3 / 24" for photos, "" when nothing is on the TV. */
    fun progress(s: CastState): String = when (s.kind) {
        CastRequest.VIDEO -> if (s.durationMs > 0) ResumeRule.clock(s.positionMs) + " / " + ResumeRule.clock(s.durationMs)
            else ResumeRule.clock(s.positionMs)
        CastRequest.PHOTOS -> if (s.count > 0) "${s.index + 1} / ${s.count}" else ""
        else -> ""
    }

    /** Phone (Remote): the slideshow interval choice closest to what the TV reports (5 s if it reports none). */
    fun intervalChoice(intervalMs: Long): Int =
        if (intervalMs <= 0) 5 else INTERVALS_S.minByOrNull { kotlin.math.abs(it * 1000L - intervalMs) }!!
}

/**
 * "Has the other side gone?" for one cast: [ok] after every answer, [failed] after every failed try; true once
 * nothing has answered for [limitMs] (counted from [startMs] if nothing ever answered). TV → phone: 10 s
 * ("Lost connection to the phone", design §5.5); phone → TV: 30 s.
 */
class Reachability(startMs: Long, private val limitMs: Long) {
    private var lastOkMs = startMs

    fun ok(nowMs: Long) {
        lastOkMs = nowMs
    }

    fun failed(nowMs: Long): Boolean = nowMs - lastOkMs >= limitMs
}

/**
 * Phone: has the cast ended on the TV (Back on the remote, or Ferry left the TV's screen)? The TV then answers
 * `kind = idle`; [idleLimit] such answers in a row end the session (one could be a moment between two casts).
 */
class CastWatch(private val idleLimit: Int = 2) {
    private var idles = 0

    /** true = the TV shows nothing any more. */
    fun onState(s: CastState): Boolean {
        if (s.kind == CastState.IDLE) idles++ else idles = 0
        return idles >= idleLimit
    }
}

/**
 * A TV address typed on the phone (audit O2, the TV's counterpart of the PC's "type the address once" [H4]):
 * "192.168.1.30" means the TV's cast receiver on its usual port [RECEIVER_PORT]; "192.168.1.30:8770" is taken as
 * typed. Anything that is not an IPv4 address → null. Pure Kotlin (TvAddressTest).
 */
object TvAddress {
    /** `CastReceiverHost.PORT`. */
    const val RECEIVER_PORT = 8766

    fun parse(text: String): HostPort? {
        val hp = HostPort.parse(text) ?: return null
        return if (text.substringAfter("://").contains(':')) hp else HostPort(hp.host, RECEIVER_PORT)
    }
}
