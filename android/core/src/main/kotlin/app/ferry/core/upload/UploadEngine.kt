package app.ferry.core.upload

import app.ferry.core.Clock
import app.ferry.core.model.CheckBatchReply
import app.ferry.core.model.CheckFile
import app.ferry.core.model.UploadReply
import app.ferry.core.net.FerryApi
import app.ferry.core.net.FerryException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's upload queue (design §4.4, plan §3.2 "Engine algorithm").
 *
 * - Pre-check with `check-batch` (chunks of 500): duplicates → AlreadyOnPc; the summed `needed_bytes` larger than
 *   `free_bytes` stops the queue before anything is sent [B3]; an HTTP error / timeout while the PC answers
 *   `info` → upload unchecked [B4].
 * - Two workers claim rows strictly in selection order. Big-file gate [H15] [PB14]: a row > [EngineConfig.bigFileBytes]
 *   is claimed in its turn; from then on the other worker claims nothing until it has ended, and the big row starts
 *   as soon as the other worker is idle.
 * - Network failure → `info()`: unreachable → WaitingForPc (probe every 3 s, re-discover every 30 s, give up after
 *   30 min [B20]); reachable → up to 3 attempts; a mid-body drop with too little free space is disk full [B11].
 * - "Body fully sent" is remembered per row: a later `skipped` counts as Saved; Cancel waits ≤ 10 s for its reply [B18].
 * - File changed (size at open or [FerryException.SourceChanged]) → refresh once and retry, then Failed [B5].
 *
 * Row state lives behind one lock (all critical sections are short and never suspend); workers, the PC prober and
 * the state publisher are coroutines in the caller's [scope]. State is published at most every
 * [EngineConfig.progressEveryMs] (and once more when the run finishes).
 */
class UploadEngine(
    private val scope: CoroutineScope,
    private val api: () -> FerryApi?,
    private val rediscover: suspend () -> FerryApi?,
    private val clock: Clock,
    private val config: EngineConfig = EngineConfig(),
    private val log: (String) -> Unit = {},
) {
    private class Row(var item: UploadItem) {
        var status: ItemStatus = ItemStatus.Waiting
        var checked = false
        var netFailures = 0
        var refreshed = false
        /** An earlier attempt of this row handed the PC every byte [B18]. */
        var bodySentOnce = false
        /** Progress of the current attempt. */
        var sent = 0L
        var job: Job? = null
        var graceJob: Job? = null
        var cancelRequested = false
        val ready: Boolean get() = checked || item.skipPrecheck
    }

    private sealed interface Claim {
        object Done : Claim
        object Recheck : Claim
        class Wait(val version: Long) : Claim
        class Got(val row: Row) : Claim
    }

    private val lock = Any()
    private val rows = ArrayList<Row>()
    private var inFlight = 0
    private var bigRow: Row? = null
    private var stop: StopReason? = null
    private var waiting = false
    private var waitStart = 0L
    private var current: FerryApi? = null
    private var pcName: String? = null
    private var runJob: Job? = null
    private var runScope: CoroutineScope? = null
    private var proberJob: Job? = null
    private var checkJob: Job? = null
    private var finishedFlag = false
    private var moved = 0L
    private val speed = SpeedMeter(config.speedWindowMs)
    private val checkMutex = Mutex()

    /** Scheduling-relevant changes (rows claimed/ended/added, gate opened/closed). */
    private val changes = MutableStateFlow(0L)

    /** Any change incl. progress: drives the state publisher. */
    private val dirty = MutableStateFlow(0L)
    private val pcAvailable = MutableStateFlow(true)
    private val finished = MutableStateFlow<Summary?>(null)

    private val _state = MutableStateFlow(
        EngineState(Phase.Idle, emptyList(), 0, 0, 0, null, null, null),
    )
    val state: StateFlow<EngineState> = _state

    // ------------------------------------------------------------------ public API

    /** Appends rows (one combined summary while running). */
    fun add(items: List<UploadItem>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            for (it in items) rows += Row(it)
            log("add ${items.size} item(s)")
            bump()
            startIfIdle()
        }
    }

    /** Re-queues AlreadyOnPc rows with the pre-check skipped [A2] [B6]. */
    fun sendAnyway(ids: Collection<Long>) {
        val wanted = ids.toHashSet()
        synchronized(lock) {
            var any = false
            for (r in rows) {
                if (r.item.id in wanted && r.status is ItemStatus.AlreadyOnPc) {
                    r.item = r.item.copy(skipPrecheck = true)
                    resetLocked(r)
                    any = true
                }
            }
            if (any) {
                bump()
                startIfIdle()
            }
        }
    }

    /** Re-queues Failed and NotSent rows (they are checked again). */
    fun retry() {
        synchronized(lock) {
            var any = false
            for (r in rows) {
                if (r.status is ItemStatus.Failed || r.status is ItemStatus.NotSent) {
                    resetLocked(r)
                    r.checked = false
                    any = true
                }
            }
            if (any) {
                bump()
                startIfIdle()
            }
        }
    }

    /** Rows not started → Cancelled; in-flight rows whose body is fully sent get ≤ 10 s for the reply [B18]. */
    fun cancel() {
        synchronized(lock) {
            if (!runningLocked()) return
            log("cancel")
            stopLocked(StopReason.Cancelled)
            for (r in rows) {
                if (r.status !is ItemStatus.Sending) continue
                r.cancelRequested = true
                val job = r.job
                if (job != null && r.sent >= r.item.size) {
                    r.graceJob = runScope?.launch {
                        delay(config.cancelGraceMs)
                        synchronized(lock) { r.job }?.cancel()
                    }
                } else {
                    job?.cancel()
                }
            }
        }
    }

    /** dataSync time limit [A6]: everything not finished → NotSent(TimeLimit), in-flight requests aborted. */
    fun stopForTimeLimit() {
        synchronized(lock) {
            if (!runningLocked()) return
            log("time limit")
            stopLocked(StopReason.TimeLimit)
            for (r in rows) {
                if (r.status !is ItemStatus.Sending) continue
                r.cancelRequested = true
                r.job?.cancel()
            }
        }
    }

    suspend fun awaitFinished(): Summary = finished.filterNotNull().first()

    // ------------------------------------------------------------------ run

    private fun runningLocked() = runJob?.isActive == true && !finishedFlag

    private fun startIfIdle() {
        if (runningLocked()) return
        stop = null
        finishedFlag = false
        finished.value = null
        waiting = false
        pcAvailable.value = true
        speed.reset()
        runJob = scope.launch { runAll() }
    }

    private suspend fun runAll(): Unit = coroutineScope {
        synchronized(lock) { runScope = this }
        val publisher = launch { publishLoop() }
        try {
            while (true) {
                if (connect()) {
                    coroutineScope { repeat(config.workers) { launch { worker() } } }
                }
                val done = synchronized(lock) {
                    proberJob?.cancel()
                    if (stop == null && rows.any { it.status is ItemStatus.Waiting || it.status is ItemStatus.Checking }) {
                        false // rows were added just as the workers ended
                    } else {
                        finishLocked()
                        true
                    }
                }
                if (done) break
            }
        } finally {
            publisher.cancel()
        }
    }

    /** First contact: info() (OldPc stops everything [B15]); no PC → WaitingForPc. False = stopped. */
    private suspend fun connect(): Boolean {
        var a = pick()
        if (a == null) {
            a = safeRediscover()
            if (a != null) synchronized(lock) { current = a }
        }
        if (a == null) {
            enterWaiting("no PC known yet")
            return true
        }
        return try {
            val info = a.info()
            synchronized(lock) { pcName = info.name }
            true
        } catch (e: FerryException.OldPc) {
            stopWith(StopReason.OldPc)
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            enterWaiting("info: $e")
            true
        }
    }

    private suspend fun worker() {
        while (true) {
            pcAvailable.first { it }
            if (!ensureChecked()) continue
            when (val c = claim()) {
                Claim.Done -> return
                Claim.Recheck -> continue
                is Claim.Wait -> changes.first { it != c.version }
                is Claim.Got -> send(c.row)
            }
        }
    }

    private fun claim(): Claim {
        synchronized(lock) {
            return claimLocked()
        }
    }

    private fun claimLocked(): Claim {
        if (stop != null) return Claim.Done
        if (!pcAvailable.value || bigRow != null) return Claim.Wait(changes.value)
        val next = rows.firstOrNull { it.status is ItemStatus.Waiting || it.status is ItemStatus.Checking }
            ?: return if (inFlight == 0) Claim.Done else Claim.Wait(changes.value)
        if (next.status is ItemStatus.Checking) return Claim.Wait(changes.value)
        if (!next.ready) return Claim.Recheck
        next.status = ItemStatus.Sending(0, next.item.size)
        next.sent = 0
        next.cancelRequested = false
        inFlight++
        if (next.item.size > config.bigFileBytes) bigRow = next
        bump()
        return Claim.Got(next)
    }

    // ------------------------------------------------------------------ pre-check (step 3)

    private fun needsCheck(r: Row) = r.status is ItemStatus.Waiting && !r.checked && !r.item.skipPrecheck

    /** True: go on and claim. False: loop (the engine is now waiting for the PC or stopped). */
    private suspend fun ensureChecked(): Boolean {
        if (synchronized(lock) { rows.none { needsCheck(it) } }) return true
        checkMutex.withLock {
            val batch = synchronized(lock) {
                if (stop != null) return true
                val b = rows.filter { needsCheck(it) }
                for (r in b) r.status = ItemStatus.Checking
                if (b.isNotEmpty()) bump()
                b
            }
            if (batch.isEmpty()) return true
            val a = pick()
            if (a == null) {
                revert(batch)
                enterWaiting("no PC for check-batch")
                return false
            }
            var needed = 0L
            var free: Long? = null
            val chunks = batch.chunked(config.checkChunk)
            for ((index, chunk) in chunks.withIndex()) {
                val reply: CheckBatchReply = try {
                    checkCall(a, chunk)
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    revert(batch)
                    return false
                } catch (e: Exception) {
                    if (e !is FerryException.Unreachable && answers(a)) {
                        // [B4] the PC is there but could not check: upload unchecked (its hash still dedupes)
                        log("check-batch failed ($e); uploading unchecked")
                        synchronized(lock) {
                            for (r in chunks.drop(index).flatten()) {
                                if (r.status !is ItemStatus.Checking) continue
                                r.checked = true
                                r.status = ItemStatus.Waiting
                            }
                            bump()
                        }
                        break
                    }
                    revert(batch)
                    enterWaiting("check-batch: $e")
                    return false
                }
                synchronized(lock) {
                    for ((k, r) in chunk.withIndex()) {
                        if (r.status !is ItemStatus.Checking) continue // cancelled meanwhile
                        val dup = reply.results[k].duplicate
                        r.checked = true
                        r.status = if (dup != null) ItemStatus.AlreadyOnPc(dup) else ItemStatus.Waiting
                    }
                    bump()
                }
                needed += reply.neededBytes
                if (reply.freeBytes != null) free = reply.freeBytes
            }
            val f = free
            if (f != null && needed > f) {
                log("not enough space: needed $needed, free $f")
                stopWith(StopReason.DiskFull(needed - f)) // [B3] nothing sent
            }
            return true
        }
    }

    private suspend fun checkCall(a: FerryApi, chunk: List<Row>): CheckBatchReply = supervisorScope {
        val files = chunk.map { CheckFile(it.item.name, it.item.size, it.item.mtimeMs) }
        val d = async { a.checkBatch(files) }
        synchronized(lock) {
            checkJob = d
            if (stop != null) d.cancel()
        }
        try {
            d.await()
        } finally {
            synchronized(lock) { checkJob = null }
        }
    }

    private fun revert(batch: List<Row>): Unit = synchronized(lock) {
        for (r in batch) if (r.status is ItemStatus.Checking) r.status = ItemStatus.Waiting
        bump()
    }

    // ------------------------------------------------------------------ one upload (step 5)

    private suspend fun send(row: Row) {
        if (row === synchronized(lock) { bigRow }) {
            // [H15] [PB14] start as soon as the other worker is idle
            while (true) {
                var v = -1L
                synchronized(lock) {
                    if (row.cancelRequested || stop != null) {
                        endLocked(row, statusFor(stop ?: StopReason.Cancelled))
                        return
                    }
                    if (inFlight > 1) v = changes.value
                }
                if (v < 0) break
                changes.first { it != v }
            }
        }
        val a = pick()
        if (a == null) {
            synchronized(lock) { requeueLocked(row) }
            enterWaiting("no PC for upload")
            return
        }
        val item = synchronized(lock) { row.item }
        val opened = try {
            item.source.open()
        } catch (e: CancellationException) {
            throw e
        } catch (e: FerryException.OriginalUnreadable) { // [PB5] never fall back to the redacted stream
            synchronized(lock) { endLocked(row, ItemStatus.Failed(FailReason.CantReadOriginal, e.cause?.toString())) }
            return
        } catch (e: Exception) {
            synchronized(lock) { endLocked(row, ItemStatus.Failed(FailReason.CantRead, (e.cause ?: e).toString())) }
            return
        }
        if (opened.length >= 0 && opened.length != item.size) {
            closeQuietly(opened)
            log("${item.name}: size ${opened.length} at open, expected ${item.size}")
            fileChanged(row)
            return
        }
        val outcome: Result<UploadReply> = try {
            supervisorScope {
                val d = async { a.upload(item, opened) { progress(row, it) } }
                val cancelNow = synchronized(lock) {
                    row.job = d
                    row.cancelRequested && row.graceJob == null
                }
                if (cancelNow) d.cancel()
                try {
                    Result.success(d.await())
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    Result.failure(e)
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }
        } finally {
            closeQuietly(opened)
            synchronized(lock) {
                row.job = null
                row.graceJob?.cancel()
                row.graceJob = null
            }
        }
        val reply = outcome.getOrNull()
        if (reply != null) {
            synchronized(lock) {
                val st = when (reply.result) {
                    "saved" -> ItemStatus.Saved(reply.savedAs, false)
                    "renamed" -> ItemStatus.Saved(reply.savedAs, true)
                    // [B18] an earlier attempt delivered every byte: this transfer did save it
                    else -> if (row.bodySentOnce) ItemStatus.Saved(reply.savedAs, false) else ItemStatus.AlreadyOnPc(reply.savedAs)
                }
                endLocked(row, st)
            }
            return
        }
        failed(row, a, outcome.exceptionOrNull()!!)
    }

    private suspend fun failed(row: Row, a: FerryApi, e: Throwable) {
        synchronized(lock) {
            if (row.cancelRequested || e is CancellationException) {
                endLocked(row, statusFor(stop ?: StopReason.Cancelled))
                return
            }
        }
        val name = row.item.name
        when (e) {
            is FerryException.SourceChanged -> {
                log("$name: file changed while sending")
                fileChanged(row)
            }
            is FerryException.OriginalUnreadable -> end(row, ItemStatus.Failed(FailReason.CantReadOriginal, e.cause?.toString()))
            is FerryException.SourceUnreadable -> end(row, ItemStatus.Failed(FailReason.CantRead, e.cause?.toString()))
            is FerryException.DiskFull -> {
                log("$name: 507 disk full")
                stopWith(StopReason.DiskFull(null))
                end(row, ItemStatus.NotSent(StopReason.DiskFull(null)))
            }
            is FerryException.OldPc -> {
                stopWith(StopReason.OldPc)
                end(row, ItemStatus.NotSent(StopReason.OldPc))
            }
            is FerryException.Http -> {
                log("$name: HTTP ${e.code} ${e.serverMessage}")
                val reason = if (e.code == 400 || e.code == 411 || e.code == 413) FailReason.BadRequest else FailReason.PcError
                end(row, ItemStatus.Failed(reason, e.serverMessage))
            }
            is FerryException.Unreachable, is FerryException.ConnectionLost, is FerryException.Timeout -> netFailure(row, a, e as FerryException)
            is FerryException.BadReply -> end(row, ItemStatus.Failed(FailReason.PcError, e.message))
            else -> end(row, ItemStatus.Failed(FailReason.PcError, e.toString()))
        }
    }

    /** Step 6. */
    private suspend fun netFailure(row: Row, a: FerryApi, e: FerryException) {
        val bytesInFlight: Boolean
        synchronized(lock) {
            bytesInFlight = row.sent > 0 || (e is FerryException.ConnectionLost && e.bytesSent > 0)
            val bodySent = (row.sent >= row.item.size && row.sent > 0) ||
                (e is FerryException.ConnectionLost && e.bodyFullySent) ||
                (e is FerryException.Timeout && e.phase == "response")
            if (bodySent) row.bodySentOnce = true
        }
        log("${row.item.name}: $e (sent ${row.sent})")
        val info = try {
            a.info()
        } catch (x: FerryException.OldPc) {
            stopWith(StopReason.OldPc)
            end(row, ItemStatus.NotSent(StopReason.OldPc))
            return
        } catch (x: CancellationException) {
            throw x
        } catch (x: Exception) {
            null
        }
        if (info == null) {
            synchronized(lock) { requeueLocked(row) }
            enterWaiting("upload: $e")
            return
        }
        val free = info.freeBytes
        if (bytesInFlight && free != null && free < row.item.size + config.diskMargin) { // [B11]
            log("${row.item.name}: dropped mid-body and PC has $free bytes free: disk full")
            stopWith(StopReason.DiskFull(null))
            end(row, ItemStatus.NotSent(StopReason.DiskFull(null)))
            return
        }
        synchronized(lock) {
            row.netFailures++
            if (row.netFailures >= config.maxNetFailures) {
                endLocked(row, ItemStatus.Failed(FailReason.ConnectionKeptDropping, e.toString()))
            } else {
                requeueLocked(row)
            }
        }
    }

    /** [B5] refresh size + date once and retry; the second time → Failed(FileChanged). */
    private fun fileChanged(row: Row) {
        val again = synchronized(lock) { row.refreshed }
        if (again) {
            end(row, ItemStatus.Failed(FailReason.FileChanged))
            return
        }
        val meta = try {
            row.item.source.refresh()
        } catch (e: Exception) {
            end(row, ItemStatus.Failed(FailReason.CantRead, e.toString()))
            return
        }
        synchronized(lock) {
            row.refreshed = true
            row.item = row.item.copy(size = meta.size, mtimeMs = meta.mtimeMs)
            requeueLocked(row)
        }
    }

    private fun progress(row: Row, sent: Long): Unit = synchronized(lock) {
        val delta = sent - row.sent
        if (delta > 0) {
            moved += delta
            speed.add(clock.nowMs(), moved)
        }
        row.sent = sent
        if (row.status is ItemStatus.Sending) row.status = ItemStatus.Sending(sent, row.item.size)
        dirty.value = dirty.value + 1
    }

    // ------------------------------------------------------------------ waiting for the PC (step 6)

    private fun enterWaiting(why: String) {
        synchronized(lock) {
            if (stop != null || waiting) return
            waiting = true
            waitStart = clock.nowMs()
            pcAvailable.value = false
            log("waiting for PC: $why")
            bump()
            proberJob = runScope?.launch { probeLoop() }
        }
    }

    private suspend fun probeLoop() {
        var lastRediscover = synchronized(lock) { waitStart }
        while (true) {
            delay(config.probeEveryMs)
            val now = clock.nowMs()
            if (now - synchronized(lock) { waitStart } >= config.waitLimitMs) {
                log("PC not found for ${config.waitLimitMs / 60_000} min")
                stopWith(StopReason.PcNotFound) // [B20]
                return
            }
            var a = pick()
            if (now - lastRediscover >= config.rediscoverEveryMs) {
                lastRediscover = now
                val found = safeRediscover()
                if (found != null) {
                    synchronized(lock) { current = found }
                    a = found
                }
            }
            if (a == null) continue
            val ok = try {
                val info = a.info()
                synchronized(lock) { pcName = info.name }
                true
            } catch (e: FerryException.OldPc) {
                stopWith(StopReason.OldPc)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (ok) {
                synchronized(lock) {
                    waiting = false
                    proberJob = null
                    pcAvailable.value = true
                    bump()
                }
                log("PC is back")
                return
            }
        }
    }

    private suspend fun answers(a: FerryApi): Boolean = try {
        a.info()
        true
    } catch (e: FerryException.OldPc) {
        stopWith(StopReason.OldPc)
        false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun safeRediscover(): FerryApi? = try {
        rediscover()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("rediscover: $e")
        null
    }

    private fun pick(): FerryApi? = synchronized(lock) { current } ?: api()

    // ------------------------------------------------------------------ row bookkeeping (under lock)

    private fun bump() {
        changes.value = changes.value + 1
        dirty.value = dirty.value + 1
    }

    private fun statusFor(reason: StopReason): ItemStatus =
        if (reason == StopReason.Cancelled) ItemStatus.Cancelled else ItemStatus.NotSent(reason)

    private fun end(row: Row, status: ItemStatus): Unit = synchronized(lock) { endLocked(row, status) }

    private fun endLocked(row: Row, status: ItemStatus) {
        row.status = status
        row.job = null
        inFlight--
        if (bigRow === row) bigRow = null
        bump()
    }

    /** Back in the queue at its place (or NotSent/Cancelled when the queue has stopped). */
    private fun requeueLocked(row: Row) {
        endLocked(row, if (stop != null || row.cancelRequested) statusFor(stop ?: StopReason.Cancelled) else ItemStatus.Waiting)
    }

    private fun resetLocked(r: Row) {
        r.status = ItemStatus.Waiting
        r.netFailures = 0
        r.refreshed = false
        r.cancelRequested = false
        r.sent = 0
    }

    private fun stopWith(reason: StopReason): Unit = synchronized(lock) { stopLocked(reason) }

    private fun stopLocked(reason: StopReason) {
        if (stop != null) return
        stop = reason
        for (r in rows) {
            if (r.status is ItemStatus.Waiting || r.status is ItemStatus.Checking) r.status = statusFor(reason)
        }
        checkJob?.cancel()
        proberJob?.cancel()
        proberJob = null
        waiting = false
        pcAvailable.value = true
        bump()
    }

    private fun finishLocked() {
        for (r in rows) {
            if (r.status is ItemStatus.Waiting || r.status is ItemStatus.Checking) r.status = statusFor(stop ?: StopReason.Cancelled)
        }
        finishedFlag = true
        waiting = false
        val s = summaryLocked()
        log("finished: $s")
        _state.value = snapshotLocked()
        finished.value = s
    }

    // ------------------------------------------------------------------ state (step 8)

    private suspend fun publishLoop() {
        var seen = -1L
        while (true) {
            // also refresh about once a second without changes, so the speed falls during a stall
            seen = withTimeoutOrNull(1_000) { dirty.first { it != seen } } ?: seen
            val s = synchronized(lock) { if (finishedFlag) null else snapshotLocked() }
            if (s != null) _state.value = s
            delay(config.progressEveryMs)
        }
    }

    private fun snapshotLocked(): EngineState {
        val items = rows.map { ItemRow(it.item.id, it.item.name, it.item.size, it.item.original, it.status) }
        var total = 0L
        var sent = 0L
        var checking = false
        for (r in rows) {
            when (val st = r.status) {
                is ItemStatus.Saved -> {
                    total += r.item.size
                    sent += r.item.size
                }
                is ItemStatus.Sending -> {
                    total += r.item.size
                    sent += st.sent
                }
                ItemStatus.Waiting -> total += r.item.size
                ItemStatus.Checking -> {
                    total += r.item.size
                    checking = true
                }
                else -> {}
            }
        }
        val phase = when {
            finishedFlag -> Phase.Finished
            waiting -> Phase.WaitingForPc
            checking -> Phase.Checking
            runJob?.isActive == true -> Phase.Sending
            else -> Phase.Idle
        }
        val now = clock.nowMs()
        val bps = if (phase == Phase.Sending) speed.bytesPerSecond(now) else 0L
        val eta = if (bps > 0) (total - sent + bps - 1) / bps else null
        return EngineState(phase, items, sent, total, bps, eta, pcName, if (finishedFlag) summaryLocked() else null)
    }

    private fun summaryLocked(): Summary {
        var saved = 0
        var renamed = 0
        var already = 0
        var withoutLocation = 0
        var failed = 0
        var notSent = 0
        var cancelled = 0
        for (r in rows) {
            when (val st = r.status) {
                is ItemStatus.Saved -> {
                    saved++
                    if (st.renamed) renamed++
                    if (!r.item.original) withoutLocation++
                }
                is ItemStatus.AlreadyOnPc -> already++
                is ItemStatus.Failed -> failed++
                is ItemStatus.NotSent -> notSent++
                ItemStatus.Cancelled -> cancelled++
                else -> {}
            }
        }
        return Summary(saved, renamed, already, withoutLocation, failed, notSent, cancelled, stop)
    }

    private fun closeQuietly(c: java.io.Closeable) {
        try {
            c.close()
        } catch (e: Exception) {
            // ignore
        }
    }
}
