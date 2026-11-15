package app.ferry.core.upload

import app.ferry.core.Clock
import app.ferry.core.HostPort
import app.ferry.core.Opened
import app.ferry.core.download.DownloadSink
import app.ferry.core.model.AppInfo
import app.ferry.core.model.CheckBatchReply
import app.ferry.core.model.CheckFile
import app.ferry.core.model.CheckResult
import app.ferry.core.model.OutboxList
import app.ferry.core.model.PcInfo
import app.ferry.core.model.UploadReply
import app.ferry.core.net.FerryApi
import app.ferry.core.net.FerryException
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import java.net.ConnectException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Fake PC for [UploadEngineTest]: simulates transfer time with `delay` (virtual time under runTest). */
class FakeApi(private val clock: Clock, override val base: HostPort = HostPort("10.0.0.9")) : FerryApi {
    @Volatile var reachable = true
    @Volatile var oldPc = false
    @Volatile var infoFree: Long? = 1L shl 40
    @Volatile var checkFree: Long? = 1L shl 40
    @Volatile var checkError: (() -> Exception)? = null

    /** Simulated speed of the default transfer. */
    @Volatile var bytesPerMs: Long = 1_000

    /** name → size of files "on the PC" (check-batch duplicates; default upload answers skipped/renamed). */
    val onPc = ConcurrentHashMap<String, Long>()

    /** Per-call override of the upload; [attempt] counts calls for the same item id (1 = first). */
    @Volatile var hook: (suspend FakeApi.(attempt: Int, item: UploadItem, onProgress: (Long) -> Unit) -> UploadReply)? = null

    val infoCalls = AtomicInteger()
    val checkCalls = AtomicInteger()
    val uploadCalls = AtomicInteger()
    private val attempts = ConcurrentHashMap<Long, AtomicInteger>()

    /** "start:name@t" / "end:name@t" in order. */
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val started: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val uploadedItems: MutableList<UploadItem> = Collections.synchronizedList(mutableListOf())
    private val active = AtomicInteger()
    @Volatile var maxActive = 0

    private fun down(): Nothing = throw FerryException.Unreachable(ConnectException("fake: unreachable"))

    override suspend fun info(timeoutMs: Long): PcInfo {
        infoCalls.incrementAndGet()
        delay(5)
        if (!reachable) down()
        if (oldPc) throw FerryException.OldPc(1)
        return PcInfo(app = "ferry", protocol = 2, version = "2.0.0", name = "FAKE-PC", freeBytes = infoFree)
    }

    override suspend fun checkBatch(files: List<CheckFile>): CheckBatchReply {
        checkCalls.incrementAndGet()
        delay(20)
        if (!reachable) down()
        checkError?.let { throw it() }
        var needed = 0L
        val results = files.map { f ->
            if (onPc[f.name] == f.size) {
                CheckResult(f.name)
            } else {
                needed += f.size
                CheckResult(null)
            }
        }
        return CheckBatchReply(ok = true, results = results, neededBytes = needed, freeBytes = checkFree)
    }

    override suspend fun upload(item: UploadItem, opened: OpenedSource, onProgress: (Long) -> Unit): UploadReply {
        uploadCalls.incrementAndGet()
        uploadedItems += item
        val attempt = attempts.getOrPut(item.id) { AtomicInteger() }.incrementAndGet()
        if (!reachable) down()
        started += item.name
        events += "start:${item.name}@${clock.nowMs()}"
        val now = active.incrementAndGet()
        synchronized(this) { if (now > maxActive) maxActive = now }
        try {
            val h = hook
            return if (h != null) h(attempt, item, onProgress) else transfer(item, onProgress)
        } finally {
            active.decrementAndGet()
            events += "end:${item.name}@${clock.nowMs()}"
        }
    }

    /** The default upload: 64 KB steps at [bytesPerMs]; a drop of [reachable] mid-body → ConnectionLost. */
    suspend fun transfer(item: UploadItem, onProgress: (Long) -> Unit, from: Long = 0): UploadReply {
        var sent = from
        val step = 64L * 1024
        while (sent < item.size) {
            val n = minOf(step, item.size - sent)
            delay(maxOf(1L, n / bytesPerMs))
            if (!reachable) throw FerryException.ConnectionLost(false, sent, java.io.IOException("fake: drop"))
            sent += n
            onProgress(sent)
        }
        delay(5)
        if (!reachable) throw FerryException.ConnectionLost(true, sent, java.io.IOException("fake: drop after body"))
        return store(item)
    }

    fun store(item: UploadItem): UploadReply {
        val have = onPc[item.name]
        return when {
            have == item.size -> UploadReply("skipped", item.name, item.size)
            have != null -> {
                val dot = item.name.lastIndexOf('.')
                val renamed = item.name.substring(0, dot) + " (1)" + item.name.substring(dot)
                onPc[renamed] = item.size
                UploadReply("renamed", renamed, item.size)
            }
            else -> {
                onPc[item.name] = item.size
                UploadReply("saved", item.name, item.size)
            }
        }
    }

    override suspend fun outbox(): OutboxList = throw UnsupportedOperationException()
    override suspend fun appInfo(): AppInfo? = throw UnsupportedOperationException()
    override suspend fun download(urlPath: String, expectedSize: Long, sink: DownloadSink, onProgress: (Long) -> Unit): String =
        throw UnsupportedOperationException()
    override fun absoluteUrl(urlPath: String): String = "http://$base$urlPath"
}

/** Source of [size] bytes whose content the fake never reads; open lengths / refresh results are scriptable. */
class FakeSource(@Volatile var size: Long, override val displayName: String = "fake") : UploadSource {
    val openLengths = ArrayDeque<Long>()
    @Volatile var openError: Exception? = null
    @Volatile var refreshMeta: SourceMeta? = null
    val opens = AtomicInteger()
    val refreshes = AtomicInteger()

    override fun open(): OpenedSource {
        opens.incrementAndGet()
        openError?.let { throw it }
        val len = synchronized(openLengths) { openLengths.removeFirstOrNull() } ?: size
        return Opened(ByteArrayInputStream(ByteArray(0)), len)
    }

    override fun refresh(): SourceMeta {
        refreshes.incrementAndGet()
        val m = refreshMeta ?: return SourceMeta(size, null)
        size = m.size
        return m
    }
}
