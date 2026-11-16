package app.ferry.ui.tv

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.ferry.AppGraph
import app.ferry.R
import app.ferry.cast.CastStateMapper
import app.ferry.core.cast.CastSink
import app.ferry.core.model.CastRequest
import app.ferry.core.model.CastState
import app.ferry.core.model.ControlRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The TV's side of casting (design §5.5, plan §4.10): `CastReceiverServer` calls it on server threads; it hands the
 * cast to the UI through [show] (the TV shows it **on top of whatever is showing**, see `TvRoot`) and the phone's
 * controls through [commands]; [state] answers from a @Volatile snapshot that the cast screen refreshes every 500 ms
 * (plan §3.3: never blocks on the main thread).
 *
 * B78: the cast is a slot of its own ([show]) rather than a screen pushed on the back stack: Back on the TV, a stop
 * from the phone or a lost phone simply empty it, and in the debug loopback (phone mode, `CAST_RECEIVER`) the phone's
 * own screens stay on the back stack while the "TV" picture is drawn in a corner.
 */
object CastSinkImpl : CastSink {
    /** One cast on screen; [id] changes with every new cast (a new cast replaces the one showing). */
    data class Show(val id: Int, val req: CastRequest, val fromIp: String)

    private val main = Handler(Looper.getMainLooper())
    private val ids = AtomicInteger()

    val show = MutableStateFlow<Show?>(null)

    /** The phone's controls for the screen showing the cast (stop is handled here). */
    val commands = MutableSharedFlow<ControlRequest>(extraBufferCapacity = 32)

    /** What `GET /api/v1/state` answers. */
    @Volatile var snapshot: CastState = CastStateMapper.IDLE

    /** The activity (keep-screen-on on a cast [A10]); set by [CastReceiverHost]. */
    @Volatile internal var activity: WeakReference<Activity>? = null

    /** Tests and the log: what arrived ("cast video ×1", "control pause", …). */
    val received = CopyOnWriteArrayList<String>()

    override fun onCast(req: CastRequest, fromIp: String): Boolean {
        val s = Show(ids.incrementAndGet(), req, fromIp)
        snapshot = CastStateMapper.starting(req)
        received += "cast ${req.kind} ×${req.items.size}"
        main.post {
            show.value = s
            AppGraph.log("cast", "showing ${req.kind} ×${req.items.size} from '${req.from}' ($fromIp)")
            val from = req.from.ifBlank { fromIp }
            Toast.makeText(AppGraph.app, AppGraph.app.getString(R.string.playing_from, from), Toast.LENGTH_LONG).show()
            activity?.get()?.let { KeepScreenOn.poke(it) } // the screen stays on (the player / viewer also pin it)
        }
        return true
    }

    override fun onControl(c: ControlRequest): Boolean {
        received += "control ${c.action}"
        if (c.action == ControlRequest.STOP) {
            main.post { stop("stopped from the phone") }
            return true
        }
        if (show.value == null) return false // nothing to control: 409
        return commands.tryEmit(c)
    }

    override fun state(): CastState = snapshot

    /** Main thread: ends the cast on screen (Back on the TV, stop from the phone, phone lost, Ferry left the screen). */
    fun stop(why: String) {
        if (show.value != null) AppGraph.log("cast", "cast ended: $why")
        show.value = null
        snapshot = CastStateMapper.IDLE
    }

    /** The screen showing cast [id] reports its state (ignored once that cast has ended or been replaced). */
    fun report(id: Int, s: CastState) {
        if (show.value?.id == id) snapshot = s
    }
}
