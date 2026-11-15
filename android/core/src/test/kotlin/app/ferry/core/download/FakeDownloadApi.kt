package app.ferry.core.download

import app.ferry.core.HostPort
import app.ferry.core.model.AppInfo
import app.ferry.core.model.CheckBatchReply
import app.ferry.core.model.CheckFile
import app.ferry.core.model.OutboxList
import app.ferry.core.model.PcInfo
import app.ferry.core.model.UploadReply
import app.ferry.core.net.FerryApi
import app.ferry.core.net.FerryException
import app.ferry.core.upload.OpenedSource
import app.ferry.core.upload.UploadItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fake PC for download tests (virtual time). Bodies go through the real [SinkWriter], so the sink contract
 * [PB17] is exercised exactly as `FerryClient.download` does it. Each call takes the next [Plan] (default: OK).
 */
class FakeDownloadApi(private val content: (String) -> ByteArray) : FerryApi {
    override val base = HostPort("10.0.0.9")

    /** One attempt's behaviour. */
    class Plan(
        /** Content-Length the "PC" announces (null = the right one). */
        val contentLength: Long? = null,
        /** Bytes delivered before the body ends cleanly (null = all). */
        val deliver: Long? = null,
        /** Drop the connection after this many bytes; [reachableAfterDrop] decides whether info() still answers. */
        val dropAt: Long? = null,
        val reachableAfterDrop: Boolean = true,
        /** Suspend after this many bytes until [gate] completes (cancel tests). */
        val holdAt: Long? = null,
        val gate: CompletableDeferred<Unit> = CompletableDeferred(),
        val httpCode: Int? = null,
    )

    @Volatile var reachable = true
    val plans = ArrayDeque<Plan>()
    val calls = AtomicInteger()
    val infoCalls = AtomicInteger()
    val started: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val active = AtomicInteger()
    @Volatile var maxActive = 0

    override suspend fun info(timeoutMs: Long): PcInfo {
        infoCalls.incrementAndGet()
        delay(5)
        if (!reachable) throw FerryException.Unreachable(ConnectException("fake: unreachable"))
        return PcInfo(app = "ferry", protocol = 2, name = "FAKE-PC")
    }

    override suspend fun download(urlPath: String, expectedSize: Long, sink: DownloadSink, onProgress: (Long) -> Unit): String {
        calls.incrementAndGet()
        started += urlPath
        val now = active.incrementAndGet()
        synchronized(this) { if (now > maxActive) maxActive = now }
        try {
            delay(5)
            if (!reachable) throw FerryException.Unreachable(ConnectException("fake: unreachable"))
            val plan = synchronized(plans) { plans.removeFirstOrNull() } ?: Plan()
            plan.httpCode?.let { throw FerryException.Http(it, "fake") }
            val bytes = content(urlPath)
            val len = plan.contentLength ?: bytes.size.toLong()
            if (len != expectedSize) throw FerryException.BadReply("length $len, expected $expectedSize") // as FerryClient
            val w = SinkWriter(sink, expectedSize, onProgress)
            try {
                w.start()
                val end = minOf(plan.deliver ?: len, bytes.size.toLong())
                var pos = 0L
                while (pos < end) {
                    if (plan.holdAt != null && pos >= plan.holdAt && !plan.gate.isCompleted) plan.gate.await()
                    if (plan.dropAt != null && pos >= plan.dropAt) {
                        reachable = plan.reachableAfterDrop
                        throw FerryException.ConnectionLost(false, 0, IOException("fake: drop at $pos"))
                    }
                    val n = minOf(64L * 1024, end - pos).toInt()
                    delay(10)
                    w.write(bytes.copyOfRange(pos.toInt(), pos.toInt() + n), n)
                    pos += n
                }
                return w.finish()
            } catch (e: Throwable) {
                w.abort()
                throw e
            }
        } finally {
            active.decrementAndGet()
        }
    }

    override suspend fun checkBatch(files: List<CheckFile>): CheckBatchReply = throw UnsupportedOperationException()
    override suspend fun upload(item: UploadItem, opened: OpenedSource, onProgress: (Long) -> Unit): UploadReply =
        throw UnsupportedOperationException()
    override suspend fun outbox(): OutboxList = throw UnsupportedOperationException()
    override suspend fun appInfo(): AppInfo? = throw UnsupportedOperationException()
    override fun absoluteUrl(urlPath: String): String = "http://$base$urlPath"
}

/** Records every call in order; checks the contract [PB17] on the way. */
class RecordingSink(val label: String, private val events: MutableList<String>) : DownloadSink {
    val data = ByteArrayOutputStream()
    @Volatile var opened = false
    @Volatile var closed = false
    @Volatile var committed = false
    @Volatile var aborted = false
    @Volatile var commitWhileOpen = false
    @Volatile var abortWhileOpen = false

    override fun open(): OutputStream {
        events += "$label:open"
        opened = true
        return object : OutputStream() {
            override fun write(b: Int) = data.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = data.write(b, off, len)
            override fun close() {
                if (!closed) events += "$label:close"
                closed = true
            }
        }
    }

    override fun commit(): String {
        events += "$label:commit"
        if (opened && !closed) commitWhileOpen = true
        committed = true
        return "saved-$label"
    }

    override fun abort() {
        events += "$label:abort"
        if (opened && !closed) abortWhileOpen = true
        aborted = true
    }

    override fun openUri(): String = "uri-$label"
}
