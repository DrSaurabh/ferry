package app.ferry.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.ferry.AppGraph
import app.ferry.cast.CastPhase
import app.ferry.cast.CastPlan
import app.ferry.cast.CastProblem
import app.ferry.cast.CastWatch
import app.ferry.cast.Reachability
import app.ferry.core.cast.CastClient
import app.ferry.core.cast.CastFileServer
import app.ferry.core.model.CastItem
import app.ferry.core.model.CastRequest
import app.ferry.core.model.CastState
import app.ferry.core.model.ControlRequest
import app.ferry.core.net.FerryException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Phone → TV casting (design §3.4, §6; plan §4.4): foreground service of type `mediaPlayback` [H14] (no daily limit,
 * unlike `dataSync` [A6]). For one session it runs the [CastFileServer] (TCP 8767, or one of the next 9 ports) over the
 * picked MediaStore files, tells the TV what to play with the phone's address as the TV sees it, then asks the TV for
 * its state (every second while the Remote is visible, else every 3 s) for the Remote and the notification.
 * Started only from a visible screen or a notification action [A6]; locks as for transfers [A7] (the phone's screen
 * may be off while casting).
 *
 * Ends when: Stop (Remote or notification: `stop` to the TV, server closed); the TV shows nothing any more (Back on the
 * TV); the TV has not answered for 30 s; a new cast replaces it (one session at a time).
 */
class CastService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var locks: TransferLocks
    @Volatile private var server: CastFileServer? = null
    private var job: Job? = null
    @Volatile private var sessionId = 0
    private var stopping = false
    private var lastStartId = 0
    private val c get() = AppGraph.casting

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locks = TransferLocks(this, "cast")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        stopping = false
        lastStartId = startId
        AppGraph.log("cast", "service: ${intent?.action}")
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getIntExtra(EXTRA_SESSION, -1)
                val plan = c.take(id)
                if (plan == null) {
                    AppGraph.log("cast", "hand-over $id not found (process restarted?)")
                    if (!c.active) finish()
                } else {
                    start(id, plan)
                }
            }
            ACTION_CONTROL -> {
                val a = intent.getStringExtra(EXTRA_ACTION)
                if (a != null && c.active) c.controlAsync(ControlRequest(a)) else if (!c.active) finish()
            }
            ACTION_STOP -> stopCast()
            else -> if (!c.active) finish()
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            ServiceCompat.startForeground(this, Notifications.ID_CAST, Notifications.cast(this, c.session.value), type)
        } catch (e: Exception) { // e.g. not allowed from the background: the cast still runs while the process lives
            AppGraph.log("cast", "startForeground failed: $e")
        }
    }

    private fun start(id: Int, plan: CastPlan) {
        job?.cancel()
        closeServer()
        sessionId = id
        locks.acquire(c.scope)
        job = c.scope.launch {
            val srv = CastFileServer(plan.files, log = { AppGraph.log("cast", it) })
            val port = try {
                srv.start()
            } catch (e: IOException) {
                AppGraph.log("cast", "file server could not start: $e")
                end(id, CastProblem.CantServe)
                return@launch
            }
            server = srv
            val client = CastClient(AppGraph.httpClient(), plan.tv)
            try {
                val ip = client.localAddressTowardsTv()
                val items = plan.files.mapIndexed { i, f -> CastItem(srv.urlFor(i, ip), f.name, f.mime, f.source.length) }
                AppGraph.log("cast", "casting ${plan.kind} ×${items.size} to ${plan.tvName} at ${plan.tv}, served from $ip:$port")
                client.cast(CastRequest(plan.kind, AppGraph.deviceName, plan.intervalMs, 0, items))
            } catch (e: CancellationException) {
                throw e
            } catch (e: FerryException.Http) {
                AppGraph.log("cast", "the TV refused the cast: $e")
                end(id, CastProblem.TvRefused)
                return@launch
            } catch (e: IOException) {
                AppGraph.log("cast", "can't reach the TV: $e")
                end(id, CastProblem.CantReachTv)
                return@launch
            }
            c.client = client
            AppGraph.prefs.lastTv = plan.tv // remembered for next time [A24]
            AppGraph.prefs.lastTvName = plan.tvName
            c.update(id) { it.copy(phase = CastPhase.Playing) }
            refreshNotification()
            follow(id, client)
        }
    }

    /** Asks the TV for its state until the session ends (design §6: the Remote polls every second while visible). */
    private suspend fun follow(id: Int, client: CastClient) {
        val watch = CastWatch()
        val reach = Reachability(SystemClock.elapsedRealtime(), LOST_TV_MS)
        var shown: Any? = null
        while (true) {
            delay(if (c.remoteVisible.get() > 0) 1_000 else 3_000)
            val st = try {
                client.state()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (reach.failed(SystemClock.elapsedRealtime())) {
                    AppGraph.log("cast", "the TV has not answered for ${LOST_TV_MS / 1000} s: $e")
                    end(id, CastProblem.LostTv)
                    return
                }
                continue
            }
            reach.ok(SystemClock.elapsedRealtime())
            if (st.kind != CastState.IDLE) c.update(id) { it.copy(tvState = st) } // the Remote keeps the last item shown
            if (watch.onState(st)) {
                AppGraph.log("cast", "the TV shows nothing any more: session over")
                end(id, CastProblem.StoppedOnTv)
                return
            }
            val now = Triple(st.title, st.playing, st.index)
            if (now != shown) {
                shown = now
                refreshNotification()
            }
        }
    }

    /** Stop on the Remote or the notification: `stop` to the TV (at most 3 s), then the session ends. */
    private fun stopCast() {
        val id = sessionId
        if (!c.active) {
            finish()
            return
        }
        job?.cancel()
        c.scope.launch {
            withTimeoutOrNull(3_000) { c.control(ControlRequest(ControlRequest.STOP)) }
            end(id, null)
        }
    }

    /** Session [id] is over: published, then the service stops (unless a newer session took over). */
    private fun end(id: Int, problem: CastProblem?) {
        c.update(id) { it.copy(phase = CastPhase.Ended, problem = problem) }
        main.post { if (sessionId == id) finish() }
    }

    /** Main thread. Stops unless a newer start arrived meanwhile (then that one carries on) [PB10]. */
    private fun finish() {
        if (stopping) return
        if (!stopSelfResult(lastStartId)) return
        stopping = true
        job?.cancel()
        closeServer()
        c.client = null
        locks.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        AppGraph.log("cast", "service stopped")
    }

    private fun refreshNotification() {
        main.post { if (!stopping) Notifications.notifyCast(this, Notifications.cast(this, c.session.value)) }
    }

    private fun closeServer() {
        server?.close()
        server = null
    }

    override fun onDestroy() {
        job?.cancel()
        closeServer()
        c.client = null
        locks.release()
        if (c.active) c.update(sessionId) { it.copy(phase = CastPhase.Ended) } // stopped by the system
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "app.ferry.action.CAST_START"
        const val ACTION_CONTROL = "app.ferry.action.CAST_CONTROL"
        const val ACTION_STOP = "app.ferry.action.CAST_STOP"
        const val EXTRA_SESSION = "app.ferry.extra.CAST_SESSION"
        const val EXTRA_ACTION = "app.ferry.extra.CAST_ACTION"
        private const val LOST_TV_MS = 30_000L

        /** From a visible screen (Remote's Stop) [A6]. */
        fun stop(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, CastService::class.java).setAction(ACTION_STOP))
        }

        fun start(ctx: Context, sessionId: Int) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, CastService::class.java).setAction(ACTION_START).putExtra(EXTRA_SESSION, sessionId))
        }
    }
}
