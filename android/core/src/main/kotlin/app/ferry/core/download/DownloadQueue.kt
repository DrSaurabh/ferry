package app.ferry.core.download

import app.ferry.core.Clock
import app.ferry.core.model.OutFile
import app.ferry.core.net.FerryApi
import app.ferry.core.net.FerryException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

sealed interface DownloadStatus {
    object Queued : DownloadStatus
    data class Running(val got: Long, val total: Long) : DownloadStatus
    data class Done(val savedAs: String, val openUri: String) : DownloadStatus

    /** [message] is one of the [DownloadFail] codes; `:app` maps it to words (§4.12). */
    data class Failed(val message: String) : DownloadStatus
    object Cancelled : DownloadStatus
}

/** Codes of [DownloadStatus.Failed] (the UI never shows exception text, plan §3.4). */
object DownloadFail {
    const val NOT_ENOUGH_SPACE = "not_enough_space"
    const val PC_NOT_FOUND = "pc_not_found"
    const val KEPT_DROPPING = "connection_kept_dropping"
    const val GONE_FROM_PC = "gone_from_pc"
    const val CHANGED_ON_PC = "changed_on_pc"
    const val PC_ERROR = "pc_error"
    const val CANT_SAVE = "cant_save"
}

/**
 * Outbox downloads, one at a time (design §4.5, plan §3.2):
 * - every attempt gets a fresh sink from `sinkFactory` (which throws `NotEnoughSpace` when the phone is too full);
 * - the body must be exactly `file.size` bytes, else the sink is aborted, never committed [B7];
 * - a network drop (incl. a silent stall, noticed by the client's 30 s read timeout [PB11]) aborts the sink, then:
 *   PC still answers → start again from zero at once (3 drops in a row → Failed); PC gone → wait for it
 *   (probe `info()` every 3 s, call [waitForPc] every 30 s, give up after 30 min) and start again from zero in a
 *   fresh sink [H20]. There is no resume.
 * - cancel aborts the sink (after its stream is closed [PB17]).
 * [waitForPc] is the re-discovery call (like the upload engine's `rediscover`); a client it returns is used from
 * then on.
 */
class DownloadQueue(
    private val scope: CoroutineScope,
    private val api: () -> FerryApi?,
    private val waitForPc: suspend () -> FerryApi?,
    private val clock: Clock,
    private val waitLimitMs: Long = 30 * 60_000L,
    private val probeEveryMs: Long = 3_000,
    private val rediscoverEveryMs: Long = 30_000,
    private val maxNetFailures: Int = 3,
    private val progressEveryMs: Long = 250,
    private val log: (String) -> Unit = {},
) {
    private class Entry(val file: OutFile, val sinkFactory: (OutFile) -> DownloadSink)

    private val lock = Any()
    private val pending = ArrayDeque<Entry>()
    private var current: Entry? = null
    private var currentJob: Job? = null
    private var runner: Job? = null
    private var found: FerryApi? = null

    private val _state = MutableStateFlow<Map<Int, DownloadStatus>>(emptyMap())
    val state: StateFlow<Map<Int, DownloadStatus>> = _state.asStateFlow()

    private val _waiting = MutableStateFlow(false)

    /** True while a download waits for the PC to come back (Ph3a, B33: the UI says "Waiting for the PC…"). */
    val waitingForPc: StateFlow<Boolean> = _waiting.asStateFlow()

    fun enqueue(file: OutFile, sinkFactory: (OutFile) -> DownloadSink) {
        synchronized(lock) {
            val st = _state.value[file.id]
            if (st is DownloadStatus.Queued || st is DownloadStatus.Running) return // already in the queue
            pending.addLast(Entry(file, sinkFactory))
            set(file.id, DownloadStatus.Queued)
            if (runner == null) {
                runner = scope.launch { runLoop() }
            }
        }
    }

    fun cancel(id: Int) {
        synchronized(lock) {
            val e = pending.firstOrNull { it.file.id == id }
            if (e != null) {
                pending.remove(e)
                set(id, DownloadStatus.Cancelled)
            } else if (current?.file?.id == id) {
                currentJob?.cancel()
            }
        }
    }

    fun cancelAll() {
        synchronized(lock) {
            for (e in pending) set(e.file.id, DownloadStatus.Cancelled)
            pending.clear()
            currentJob?.cancel()
        }
    }

    // ------------------------------------------------------------------ worker

    private suspend fun runLoop() {
        while (true) {
            val (e, job) = synchronized(lock) {
                val next = pending.removeFirstOrNull()
                if (next == null) {
                    current = null
                    currentJob = null
                    runner = null
                    return
                }
                val j = scope.launch(start = CoroutineStart.LAZY) { process(next) }
                current = next
                currentJob = j
                next to j
            }
            job.start()
            job.join()
            synchronized(lock) {
                if (job.isCancelled) {
                    val st = _state.value[e.file.id]
                    if (st !is DownloadStatus.Done && st !is DownloadStatus.Failed) set(e.file.id, DownloadStatus.Cancelled)
                }
                current = null
                currentJob = null
            }
        }
    }

    private suspend fun process(e: Entry) {
        val id = e.file.id
        var drops = 0
        while (true) {
            val a = pick() ?: if (awaitPc()) continue else return fail(id, DownloadFail.PC_NOT_FOUND)
            val sink = try {
                GuardedSink(e.sinkFactory(e.file))
            } catch (x: FerryException.NotEnoughSpace) {
                return fail(id, DownloadFail.NOT_ENOUGH_SPACE)
            } catch (x: Exception) {
                log("download ${e.file.name}: sink: $x")
                return fail(id, DownloadFail.CANT_SAVE)
            }
            set(id, DownloadStatus.Running(0, e.file.size))
            // Progress comes from the OkHttp thread, which may still run briefly after a cancel: once this attempt
            // is over ([live] false, set before any other status), its late progress calls change nothing.
            val live = AtomicBoolean(true)
            var lastProgress = Long.MIN_VALUE / 2
            try {
                val name = try {
                    a.download(e.file.url, e.file.size, sink) { got ->
                        val now = clock.nowMs()
                        if (got == e.file.size || now - lastProgress >= progressEveryMs) {
                            lastProgress = now
                            _state.update { if (live.get()) it + (id to DownloadStatus.Running(got, e.file.size)) else it }
                        }
                    }
                } finally {
                    live.set(false)
                }
                set(id, DownloadStatus.Done(name, sink.openUri()))
                log("download ${e.file.name}: saved as $name")
                return
            } catch (x: CancellationException) {
                sink.abort() // after the stream is closed [PB17]
                if (sink.committed) set(id, DownloadStatus.Done(sink.name ?: e.file.name, sink.openUri())) // finished first
                throw x
            } catch (x: FerryException) {
                sink.abort()
                log("download ${e.file.name}: $x")
                when (x) {
                    is FerryException.ConnectionLost, is FerryException.Timeout, is FerryException.Unreachable -> {
                        set(id, DownloadStatus.Queued)
                        if (answers(a)) {
                            if (++drops >= maxNetFailures) return fail(id, DownloadFail.KEPT_DROPPING)
                        } else if (!awaitPc()) {
                            return fail(id, DownloadFail.PC_NOT_FOUND)
                        }
                        continue // from zero, in a fresh sink [H20]
                    }
                    is FerryException.Http -> return fail(id, if (x.code == 404) DownloadFail.GONE_FROM_PC else DownloadFail.PC_ERROR)
                    is FerryException.BadReply -> return fail(id, DownloadFail.CHANGED_ON_PC)
                    is FerryException.NotEnoughSpace -> return fail(id, DownloadFail.NOT_ENOUGH_SPACE)
                    else -> return fail(id, DownloadFail.PC_ERROR)
                }
            } catch (x: Exception) {
                sink.abort()
                log("download ${e.file.name}: $x")
                return fail(id, DownloadFail.CANT_SAVE)
            }
        }
    }

    /** Waits for the PC (probe every 3 s, re-discover every 30 s); false after [waitLimitMs]. */
    private suspend fun awaitPc(): Boolean {
        _waiting.value = true
        try {
            val start = clock.nowMs()
            var lastRediscover = start
            log("download: waiting for the PC")
            while (true) {
                delay(probeEveryMs)
                val now = clock.nowMs()
                if (now - start >= waitLimitMs) return false
                if (now - lastRediscover >= rediscoverEveryMs) {
                    lastRediscover = now
                    val f = try {
                        waitForPc()
                    } catch (x: CancellationException) {
                        throw x
                    } catch (x: Exception) {
                        null
                    }
                    if (f != null) synchronized(lock) { found = f }
                }
                val a = pick() ?: continue
                if (answers(a)) return true
            }
        } finally {
            _waiting.value = false
        }
    }

    private suspend fun answers(a: FerryApi): Boolean = try {
        a.info()
        true
    } catch (x: CancellationException) {
        throw x
    } catch (x: Exception) {
        false
    }

    private fun pick(): FerryApi? = synchronized(lock) { found } ?: api()

    private fun set(id: Int, st: DownloadStatus) = _state.update { it + (id to st) }

    private fun fail(id: Int, code: String) = set(id, DownloadStatus.Failed(code))

    /**
     * Keeps the [DownloadSink] contract when a cancel races the OkHttp thread that is still writing: an abort
     * while the stream is open is held until that stream is closed [PB17]; abort is idempotent and also covers
     * a sink that was never opened; nothing is committed after an abort.
     */
    internal class GuardedSink(private val inner: DownloadSink) : DownloadSink {
        private var opened = false
        private var open = false
        private var abortPending = false
        private var done = false
        var name: String? = null
            private set
        val committed: Boolean get() = synchronized(this) { name != null }

        @Synchronized
        override fun open(): OutputStream {
            check(!opened && !done) { "sink already used" }
            val o = inner.open()
            opened = true
            open = true
            return object : OutputStream() {
                private var closed = false
                override fun write(b: Int) = o.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = o.write(b, off, len)
                override fun flush() = o.flush()
                override fun close() {
                    if (closed) return
                    closed = true
                    try {
                        o.close()
                    } finally {
                        closedNow()
                    }
                }
            }
        }

        @Synchronized
        private fun closedNow() {
            open = false
            if (abortPending) doAbort()
        }

        @Synchronized
        override fun commit(): String {
            check(!open) { "commit on an open stream" }
            if (done || abortPending) throw IOException("download was cancelled")
            val n = inner.commit()
            name = n
            done = true
            return n
        }

        @Synchronized
        override fun abort() {
            if (done) return
            if (open) abortPending = true else doAbort()
        }

        private fun doAbort() {
            done = true
            abortPending = false
            try {
                inner.abort()
            } catch (x: Exception) {
                // nothing more we can do
            }
        }

        override fun openUri(): String = inner.openUri()
    }
}
