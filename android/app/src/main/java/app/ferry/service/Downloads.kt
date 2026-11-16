package app.ferry.service

import app.ferry.core.SystemClock
import app.ferry.core.download.DownloadQueue
import app.ferry.core.download.DownloadSink
import app.ferry.core.download.DownloadStatus
import app.ferry.core.model.OutFile
import app.ferry.core.net.FerryApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * The process-wide outbox download queue (`AppGraph.downloads`, design §4.5, plan §4.4), driven by `TransferService`
 * (`ACTION_DOWNLOAD`), shown by the From PC screen. One file at a time; the queue's rules are `:core`'s
 * [DownloadQueue] (exact length, fresh entry after a drop, no resume [H20]).
 */
class Downloads(
    api: () -> FerryApi?,
    waitForPc: suspend () -> FerryApi?,
    private val sinkFor: (OutFile) -> DownloadSink,
    log: (String) -> Unit,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = DownloadQueue(scope, api, waitForPc, SystemClock, log = log)

    /** Status per outbox id (the last download of that id). */
    val state: StateFlow<Map<Int, DownloadStatus>> = queue.state
    val waitingForPc: StateFlow<Boolean> = queue.waitingForPc

    /** The files of the downloads started in this process, by id (names and sizes for the notification). */
    private val files = ConcurrentHashMap<Int, OutFile>()

    /** Queued or running downloads (checked without suspending; `enqueue` publishes Queued at once). */
    val busy: Boolean get() = state.value.values.any { it is DownloadStatus.Queued || it is DownloadStatus.Running }

    fun enqueue(list: List<OutFile>) {
        for (f in list) {
            files[f.id] = f
            queue.enqueue(f, sinkFor)
        }
    }

    fun file(id: Int): OutFile? = files[id]

    fun cancel(id: Int) = queue.cancel(id)
    fun cancelAll() = queue.cancelAll()
}
