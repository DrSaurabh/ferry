package app.ferry.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import app.ferry.AppGraph
import app.ferry.core.upload.Phase
import app.ferry.core.upload.StopReason
import app.ferry.media.UploadItemFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service, type `dataSync` (design §3.4, plan §4.4): keeps the process alive while [Transfers] uploads,
 * holds the locks, shows the progress notification (≤ 1 update/s) and keeps the interrupted-transfer marker [A8].
 *
 * - Started only from a visible screen or a notification action [A6]; `START_NOT_STICKY` (the queue is in memory
 *   [H16]); a null intent stops at once.
 * - Idle → `stopSelfResult(newest start id)` through [StopDecision] [PB10]; only when that succeeded: foreground
 *   removed, then the Results notification. Work that arrives late is never dropped.
 * - `onTimeout` (Android 15 `dataSync` limit) → remaining files "Not sent: time limit", stop at once [A6].
 * - Destroyed while files are still going (user stopped it) → Cancel, not a crash [A6].
 * Downloads (`ACTION_DOWNLOAD`, Ph7) are not handled yet.
 */
class TransferService : Service() {
    private val decision = StopDecision()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var locks: TransferLocks
    private var ticker: Job? = null
    private var stopping = false
    private val t get() = AppGraph.transfers

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locks = TransferLocks(this, "transfer")
        ticker = t.scope.launch { tick() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        decision.started(startId) { handle(intent) }
        if (t.busy) locks.acquire(t.scope)
        main.post { maybeStop() }
        return START_NOT_STICKY
    }

    private fun handle(i: Intent) {
        AppGraph.log("transfer", "service: ${i.action}")
        when (i.action) {
            ACTION_SEND_PICKS -> {
                val items = t.take(i.getLongExtra(EXTRA_TOKEN, -1))
                if (items == null) AppGraph.log("transfer", "hand-over not found (process restarted?)") else t.add(items)
            }
            ACTION_SEND_ANYWAY -> t.sendAnyway(i.getLongArrayExtra(EXTRA_IDS)?.toList() ?: emptyList())
            ACTION_RETRY -> t.retry()
            ACTION_CANCEL -> t.cancel()
            else -> AppGraph.log("transfer", "ignored ${i.action}")
        }
    }

    private fun goForeground() {
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            ServiceCompat.startForeground(this, Notifications.ID_PROGRESS, Notifications.progress(this, t.state.value), type)
        } catch (e: Exception) { // e.g. not allowed from the background: the work still runs while the process lives
            AppGraph.log("transfer", "startForeground failed: $e")
        }
    }

    /** Once a second: locks, progress notification, interrupted marker (every 5 s), idle check. */
    private suspend fun tick() {
        var n = 0
        var shown: Any? = null
        while (true) {
            delay(1_000)
            n++
            val s = t.state.value
            val busy = t.busy
            if (busy) {
                locks.acquire(t.scope)
                if (s !== shown && !stopping) {
                    Notifications.update(this, s)
                    shown = s
                }
                if (n % 5 == 0) AppGraph.prefs.interruptedCount = Transfers.remaining(s)
            } else {
                main.post { maybeStop() }
            }
        }
    }

    private fun maybeStop() {
        if (stopping) return
        val id = decision.stopId { !t.busy } ?: return
        if (!stopSelfResult(id)) return // a newer start arrived: carry on [PB10]
        stopping = true
        locks.release()
        AppGraph.prefs.interruptedCount = 0
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val s = t.state.value
        val sum = s?.summary
        AppGraph.log("transfer", "service stopped: $sum")
        if (s != null && sum != null && sum.stop != StopReason.Cancelled && s.phase == Phase.Finished) {
            Notifications.postResults(this, sum, s.pcName ?: getString(app.ferry.R.string.your_pc))
        }
        File(cacheDir, UploadItemFactory.SHARE_CACHE).listFiles()?.forEach { it.delete() }
    }

    override fun onTimeout(startId: Int, fgsType: Int) { // API 35: the dataSync daily limit [A6] [PR15]
        AppGraph.log("transfer", "dataSync time limit reached")
        t.stopForTimeLimit()
        stopSelf()
    }

    override fun onDestroy() {
        ticker?.cancel()
        if (t.busy) { // stopped by the user ("Active apps") or the system: Cancel, not a crash [A6]
            AppGraph.log("transfer", "service destroyed while busy: cancel")
            t.cancel()
        }
        locks.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SEND_PICKS = "app.ferry.action.SEND_PICKS"
        const val ACTION_SEND_ANYWAY = "app.ferry.action.SEND_ANYWAY"
        const val ACTION_RETRY = "app.ferry.action.RETRY"
        const val ACTION_CANCEL = "app.ferry.action.CANCEL"
        const val EXTRA_TOKEN = "app.ferry.extra.TOKEN"
        const val EXTRA_IDS = "app.ferry.extra.IDS"
    }
}
