package app.ferry.cast

import app.ferry.core.HostPort
import app.ferry.core.cast.CastClient
import app.ferry.core.cast.CastFile
import app.ferry.core.model.CastState
import app.ferry.core.model.ControlRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class CastPhase { Starting, Playing, Ended }

/** Why a cast ended other than by the phone's Stop (shown on the Remote). */
enum class CastProblem { CantServe, CantReachTv, TvRefused, StoppedOnTv, LostTv }

/** One Play on TV session (design §6), as the Remote and the notification show it. */
data class CastSession(
    val id: Int,
    val tv: HostPort,
    val tvName: String,
    val kind: String,
    val count: Int,
    val phase: CastPhase,
    /** The TV's last `GET /api/v1/state` answer. */
    val tvState: CastState? = null,
    /** Set when the session ended by itself (null = still going, or stopped with Stop). */
    val problem: CastProblem? = null,
)

/** What the picker hands to CastService (in-process, like the send hand-over [CP8]). */
class CastPlan(val tv: HostPort, val tvName: String, val kind: String, val files: List<CastFile>, val intervalMs: Long)

/**
 * The process-wide cast state (`AppGraph.cast` of the plan, `AppGraph.casting` here): the current [session] for the
 * Remote, Home and the notification, the hand-over of a [CastPlan] to `CastService`, and [control] for the Remote's
 * buttons (the service registers its [client]).
 */
class Casting(private val log: (String) -> Unit) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _session = MutableStateFlow<CastSession?>(null)
    val session: StateFlow<CastSession?> = _session
    private val ids = AtomicInteger()
    private val plans = ConcurrentHashMap<Int, CastPlan>()

    /** The TV of the running session (set by CastService once the cast was accepted). */
    @Volatile var client: CastClient? = null

    /** Remote screens on display: the service then asks the TV for its state every second (else every 3 s). */
    val remoteVisible = AtomicInteger()

    /** A cast is starting or running (update banner "busy" rule [B21], Home's card). */
    val active: Boolean get() = _session.value?.phase.let { it == CastPhase.Starting || it == CastPhase.Playing }

    /** Registers [plan] as the new session (Starting) and returns the id the service takes it with. */
    fun handOver(plan: CastPlan): Int {
        val id = ids.incrementAndGet()
        plans[id] = plan
        _session.value = CastSession(id, plan.tv, plan.tvName, plan.kind, plan.files.size, CastPhase.Starting)
        return id
    }

    fun take(id: Int): CastPlan? = plans.remove(id)

    /** Screenshot tour only (Ph10): shows [s] on the Remote / Home without a running cast (null clears it). */
    @androidx.annotation.VisibleForTesting
    fun showForTest(s: CastSession?) {
        _session.value = s
    }

    /** Changes session [id] (ignored once a newer session replaced it). */
    fun update(id: Int, f: (CastSession) -> CastSession) {
        synchronized(this) {
            val s = _session.value
            if (s != null && s.id == id) _session.value = f(s)
        }
    }

    /** One control to the TV (Remote buttons, notification actions); false if there is no cast or the TV didn't take it. */
    suspend fun control(c: ControlRequest): Boolean {
        val cl = client ?: return false
        return try {
            cl.control(c)
            log("control ${c.action}${c.deltaMs?.let { " $it ms" } ?: ""}${c.intervalMs?.let { " interval $it ms" } ?: ""}")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            log("control ${c.action} failed: $e")
            false
        }
    }

    fun controlAsync(c: ControlRequest) {
        scope.launch { control(c) }
    }
}
