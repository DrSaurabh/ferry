package app.ferry.ui.phone

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ferry.AppGraph
import app.ferry.R
import app.ferry.cast.CastPhase
import app.ferry.cast.CastPlan
import app.ferry.cast.CastProblem
import app.ferry.core.HostPort
import app.ferry.core.cast.CastClient
import app.ferry.core.model.CastRequest
import app.ferry.core.model.ControlRequest
import app.ferry.media.CastFiles
import app.ferry.media.MediaPick
import app.ferry.media.PickerMode
import app.ferry.service.CastService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** A TV to cast to: its receiver's address and name. */
data class TvChoice(val hp: HostPort, val name: String)

/**
 * Play on TV and the Remote (plan §4.3, design §4.1.5, §6): finds the TV (the **remembered last TV first**, used at
 * once if it answers [A24]; otherwise a broadcast for `device_type "tv"`, one TV → chosen, several → the user picks),
 * hands the picked items to `CastService`, and sends the Remote's controls. Activity-scoped (shared by both screens).
 */
class CastViewModel : ViewModel() {
    val finding = MutableStateFlow(false)
    val found = MutableStateFlow<List<TvChoice>>(emptyList())
    val chosen = MutableStateFlow<TvChoice?>(null)
    val preparing = MutableStateFlow(false)
    val session = AppGraph.casting.session
    private var findJob: Job? = null

    /** Finds completed so far (tests wait for the one their screen started). */
    @Volatile var findsDone = 0
        private set

    fun find() {
        findJob?.cancel()
        chosen.value = null
        found.value = emptyList()
        finding.value = true
        findJob = viewModelScope.launch {
            try {
                val last = AppGraph.prefs.lastTv
                if (last != null && answers(last)) {
                    chosen.value = TvChoice(last, AppGraph.prefs.lastTvName ?: last.host)
                    AppGraph.log("cast", "remembered TV $last answers")
                    return@launch
                }
                if (last != null) AppGraph.log("cast", "remembered TV $last does not answer: broadcast")
                val tvs = AppGraph.connection.discoverTvs().map { TvChoice(HostPort(it.host, it.port), it.name) }.distinctBy { it.hp }
                AppGraph.log("cast", "TVs found: ${tvs.map { "${it.name} at ${it.hp}" }}")
                found.value = tvs
                if (tvs.size == 1) chosen.value = tvs[0]
            } finally {
                finding.value = false
                findsDone++
            }
        }
    }

    /** The TV's receiver answers `GET /api/v1/state` within 2.5 s. */
    private suspend fun answers(hp: HostPort): Boolean = try {
        withTimeoutOrNull(2_500) { CastClient(AppGraph.httpClient(), hp).state() } != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        false
    }

    fun choose(t: TvChoice) {
        chosen.value = t
    }

    /**
     * The picker's "Play on TV" (from the visible screen [A6]): MediaStore names/types/sizes off the main thread, then
     * `CastService`; [onStarted] opens the Remote.
     */
    fun start(ctx: Context, mode: PickerMode, picks: List<MediaPick>, onStarted: () -> Unit) {
        val tv = chosen.value ?: return
        if (preparing.value || picks.isEmpty()) return
        val app = ctx.applicationContext
        viewModelScope.launch {
            preparing.value = true
            val files = try {
                withContext(Dispatchers.IO) { CastFiles.build(app.contentResolver, picks) }
            } finally {
                preparing.value = false
            }
            if (files.isEmpty()) {
                Toast.makeText(app, R.string.cast_nothing, Toast.LENGTH_LONG).show()
                return@launch
            }
            val kind = if (mode == PickerMode.CastVideo) CastRequest.VIDEO else CastRequest.PHOTOS
            val interval = AppGraph.prefs.slideshowSeconds.coerceIn(1, 60) * 1000L
            val id = AppGraph.casting.handOver(CastPlan(tv.hp, tv.name, kind, files, interval))
            AppGraph.log("cast", "play ${files.size} item(s) on ${tv.name} ($kind)")
            try {
                CastService.start(ctx, id)
            } catch (e: Exception) { // e.g. Ferry left the screen while preparing (no service start from the background)
                AppGraph.log("cast", "cast service not started: $e")
                AppGraph.casting.update(id) { it.copy(phase = CastPhase.Ended, problem = CastProblem.CantServe) }
                return@launch
            }
            onStarted()
        }
    }

    fun control(action: String, deltaMs: Long? = null, intervalMs: Long? = null) =
        AppGraph.casting.controlAsync(ControlRequest(action, deltaMs, intervalMs))

    fun stop(ctx: Context) = CastService.stop(ctx)
}
