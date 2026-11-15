package app.ferry.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Phone → TV casting (design §5.5, §6).

/** One file the TV fetches from the phone's `CastFileServer`. */
@Serializable
data class CastItem(
    val url: String,
    val name: String,
    val mime: String,
    val size: Long = -1,
)

/** `POST /api/v1/cast` body. [kind] = [VIDEO] | [PHOTOS]; [start] = index of the first item to show. */
@Serializable
data class CastRequest(
    val kind: String,
    val from: String = "",
    @SerialName("interval_ms") val intervalMs: Long = 5_000,
    val start: Int = 0,
    val items: List<CastItem>,
) {
    companion object {
        const val VIDEO = "video"
        const val PHOTOS = "photos"
    }
}

/**
 * `POST /api/v1/control` body: `{"action":"toggle|play|pause|next|prev|seek|stop|interval","delta_ms":±N,"interval_ms":N}`.
 * `seek` needs [deltaMs]; `interval` needs [intervalMs] > 0.
 */
@Serializable
data class ControlRequest(
    val action: String,
    @SerialName("delta_ms") val deltaMs: Long? = null,
    @SerialName("interval_ms") val intervalMs: Long? = null,
) {
    companion object {
        const val TOGGLE = "toggle"
        const val PLAY = "play"
        const val PAUSE = "pause"
        const val NEXT = "next"
        const val PREV = "prev"
        const val SEEK = "seek"
        const val STOP = "stop"
        const val INTERVAL = "interval"
        val ACTIONS = setOf(TOGGLE, PLAY, PAUSE, NEXT, PREV, SEEK, STOP, INTERVAL)
    }
}

/** `GET /api/v1/state` reply. [kind] = video | photos | idle. */
@Serializable
data class CastState(
    val kind: String = IDLE,
    val index: Int = 0,
    val count: Int = 0,
    val title: String? = null,
    val playing: Boolean = false,
    @SerialName("position_ms") val positionMs: Long = 0,
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("interval_ms") val intervalMs: Long = 0,
) {
    companion object {
        const val IDLE = "idle"
    }
}
