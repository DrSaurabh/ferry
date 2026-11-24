package app.ferry.service

import app.ferry.core.SystemClock
import app.ferry.core.net.FerryApi
import app.ferry.core.upload.EngineState
import app.ferry.core.upload.ItemStatus
import app.ferry.core.upload.UploadEngine
import app.ferry.core.upload.UploadItem
import app.ferry.core.upload.UploadSource
import app.ferry.log.SendEvents
import app.ferry.media.PreparedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The process-wide upload queue (`AppGraph.transfers`, design §3.4): holds the current [UploadEngine] and publishes
 * its state as `AppGraph.transfer` for the Transfer screen, Home and the notifications. `TransferService` drives it.
 *
 * Ph5a (B44): the engine lives here, not inside the service instance, so the finished summary stays on the Transfer
 * screen and **Retry / Send anyway** (screen or Results notification) still find the rows after the service has
 * stopped. A new send while the engine is running is appended (one combined summary); a new send after it finished
 * starts a fresh engine.
 */
class Transfers(
    private val api: () -> FerryApi?,
    private val rediscover: suspend () -> FerryApi?,
    private val sourceFor: (PreparedItem) -> UploadSource,
    private val log: (String) -> Unit,
    /** 2.1 send events (L1): one per engine (batch); null in tests. */
    private val newSendEvents: () -> SendEvents? = { null },
    private val pcName: () -> String? = { null },
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<EngineState?>(null)
    val state: StateFlow<EngineState?> = _state
    private var engine: UploadEngine? = null
    private var follow: Job? = null
    private var events: SendEvents? = null
    private val ids = AtomicLong()
    private val tokens = AtomicLong()
    private val handOvers = ConcurrentHashMap<Long, List<PreparedItem>>()

    /**
     * Retry was asked for (Results notification) but this process has no queue any more: Ferry was closed in the
     * meantime [H16]. The Transfer screen then says so instead of doing nothing (audit O5). Cleared by the next send.
     */
    val retryLost = MutableStateFlow(false)

    /** There is a queue (running or finished) that Retry can act on. */
    val hasQueue: Boolean get() = synchronized(this) { engine != null }

    fun newId(): Long = ids.incrementAndGet()

    /** In-process hand-over of prepared items to the service [CP8] (no binder size limit). */
    fun handOver(items: List<PreparedItem>): Long {
        val t = tokens.incrementAndGet()
        handOvers[t] = items
        return t
    }

    fun take(token: Long): List<PreparedItem>? = handOvers.remove(token)

    /** Work in progress (checked without suspending, see `UploadEngine.isRunning`). */
    val busy: Boolean get() = synchronized(this) { engine?.isRunning == true }

    fun add(items: List<PreparedItem>) {
        if (items.isEmpty()) return
        synchronized(this) { addLocked(items) }
    }

    private fun addLocked(items: List<PreparedItem>) {
        retryLost.value = false
        var e = engine
        if (e == null || !e.isRunning) {
            follow?.cancel()
            val fresh = UploadEngine(scope, api, rediscover, SystemClock, log = log)
            e = fresh
            engine = fresh
            val ev = newSendEvents()
            events = ev
            _state.value = fresh.state.value
            follow = scope.launch {
                fresh.state.collect {
                    _state.value = it
                    ev?.onState(it)
                }
            }
        }
        events?.added(items.size, items.sumOf { it.size }, pcName())
        e.add(items.map { UploadItem(it.id, sourceFor(it), it.name, it.size, it.mtimeMs, it.original, skipPrecheck = it.unreadable) })
    }

    fun sendAnyway(ids: Collection<Long>) { synchronized(this) { engine?.sendAnyway(ids) } }
    fun retry() { synchronized(this) { engine?.retry() } }
    fun cancel() { synchronized(this) { engine?.cancel() } }
    fun stopForTimeLimit() { synchronized(this) { engine?.stopForTimeLimit() } }

    companion object {
        /** Files not finished yet (the interrupted-transfer marker [A8]). */
        fun remaining(s: EngineState?): Int = s?.items?.count {
            it.status is ItemStatus.Waiting || it.status is ItemStatus.Checking || it.status is ItemStatus.Sending
        } ?: 0
    }
}
