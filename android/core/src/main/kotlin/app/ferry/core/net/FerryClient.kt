package app.ferry.core.net

import app.ferry.core.FerryJson
import app.ferry.core.HostPort
import app.ferry.core.download.DownloadSink
import app.ferry.core.model.AppInfo
import app.ferry.core.model.CheckBatchReply
import app.ferry.core.model.CheckBatchRequest
import app.ferry.core.model.CheckFile
import app.ferry.core.model.ErrorReply
import app.ferry.core.model.OutboxList
import app.ferry.core.model.PcInfo
import app.ferry.core.model.UploadReply
import app.ferry.core.upload.OpenedSource
import app.ferry.core.upload.UploadItem
import app.ferry.core.util.PercentEncode
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The PC's HTTP API (design §2, §4.4). All failures are [FerryException]s. */
interface FerryApi {
    val base: HostPort

    /** Throws FerryException.OldPc when protocol < 2 [B15]. */
    suspend fun info(timeoutMs: Long = 5_000): PcInfo

    /** 60 s call timeout [B4]. */
    suspend fun checkBatch(files: List<CheckFile>): CheckBatchReply

    suspend fun upload(item: UploadItem, opened: OpenedSource, onProgress: (Long) -> Unit): UploadReply

    /** 10 s call timeout [PB11]. */
    suspend fun outbox(): OutboxList

    /** null on 404; 10 s call timeout [PB11]. */
    suspend fun appInfo(): AppInfo?

    /** Streams [urlPath] into [sink] (contract in DownloadSink [PB17]); returns the committed name. */
    suspend fun download(urlPath: String, expectedSize: Long, sink: DownloadSink, onProgress: (Long) -> Unit): String

    /** For ExoPlayer / Coil. */
    fun absoluteUrl(urlPath: String): String
}

/**
 * [FerryApi] over OkHttp. Calls use enqueue + suspendCancellableCoroutine, so cancelling the coroutine
 * cancels the call and closes the socket (the PC then deletes its temp file). Replies are read on the
 * OkHttp thread before the coroutine resumes, so cancellation also covers reading the body.
 */
class FerryClient(http: OkHttpClient, override val base: HostPort) : FerryApi {
    /** Same pool and interceptors; a network interceptor marks "connection opened" for the error mapping. */
    private val http: OkHttpClient = http.newBuilder()
        .addNetworkInterceptor { chain ->
            chain.request().tag(CallTrace::class.java)?.connected = true
            chain.proceed(chain.request())
        }
        .build()

    /** check-batch may take a while on a big inbox [B4]. */
    private val checkHttp: OkHttpClient = this.http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()

    /** Only upload waits long for the reply: the PC may be hashing a same-named large file [PB11]. */
    private val uploadHttp: OkHttpClient = this.http.newBuilder().readTimeout(10, TimeUnit.MINUTES).build()

    override suspend fun info(timeoutMs: Long): PcInfo {
        val info = call(http, get("api/v1/info"), timeoutMs) { decode(it, PcInfo.serializer()) }
        if (info.app != "ferry") throw FerryException.BadReply("not a Ferry PC (app=${info.app})")
        if (info.protocol < 2) throw FerryException.OldPc(info.protocol)
        return info
    }

    override suspend fun checkBatch(files: List<CheckFile>): CheckBatchReply {
        val json = FerryJson.encodeToString(CheckBatchRequest.serializer(), CheckBatchRequest(files))
        val req = Request.Builder().url(url("api/v1/check-batch")).post(json.toRequestBody(JSON))
        val reply = call(checkHttp, req, 60_000) { decode(it, CheckBatchReply.serializer()) }
        if (!reply.ok || reply.results.size != files.size) {
            throw FerryException.BadReply("check-batch: ok=${reply.ok}, ${reply.results.size} of ${files.size} results")
        }
        return reply
    }

    override suspend fun upload(item: UploadItem, opened: OpenedSource, onProgress: (Long) -> Unit): UploadReply {
        val u = HttpUrl.Builder().scheme("http").host(base.host).port(base.port)
            .addPathSegments("api/v1/upload")
            .addEncodedQueryParameter("name", PercentEncode.utf8(item.name))
            .addEncodedQueryParameter("size", item.size.toString())
        if (item.mtimeMs != null) u.addEncodedQueryParameter("mtime", item.mtimeMs.toString()) // omitted when unknown [B17]
        val body = StreamingBody(opened, item.size, onProgress)
        val req = Request.Builder().url(u.build()).post(body)
        val reply = call(uploadHttp, req, 0, body) { decode(it, UploadReply.serializer()) }
        if (reply.result != "saved" && reply.result != "renamed" && reply.result != "skipped") {
            throw FerryException.BadReply("upload result ${reply.result}")
        }
        return reply
    }

    override suspend fun outbox(): OutboxList =
        call(http, get("api/v1/outbox"), 10_000) { decode(it, OutboxList.serializer()) }

    override suspend fun appInfo(): AppInfo? =
        call(http, get("api/v1/app"), 10_000) { if (it.code == 404) null else decode(it, AppInfo.serializer()) }

    override suspend fun download(urlPath: String, expectedSize: Long, sink: DownloadSink, onProgress: (Long) -> Unit): String {
        val req = Request.Builder().url(absoluteUrl(urlPath)).get()
        return call(http, req, 0) { resp ->
            if (resp.code != 200) throw httpError(resp)
            val body = resp.body ?: throw FerryException.BadReply("no body")
            val len = body.contentLength()
            if (len != expectedSize) throw FerryException.BadReply("length $len, expected $expectedSize")
            var got = 0L
            var committed = false
            val out = sink.open()
            try {
                try {
                    body.byteStream().use { input ->
                        val buf = ByteArray(StreamingBody.CHUNK)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            onProgress(got)
                        }
                    }
                } finally {
                    out.close() // FIRST close (flush + sync), then compare, then commit [PB17]
                }
                if (got != expectedSize) throw FerryException.BadReply("got $got bytes, expected $expectedSize")
                val name = sink.commit()
                committed = true
                name
            } finally {
                if (!committed) sink.abort()
            }
        }
    }

    override fun absoluteUrl(urlPath: String): String =
        "http://${base.host}:${base.port}" + (if (urlPath.startsWith("/")) urlPath else "/$urlPath")

    // ---------------------------------------------------------------- plumbing

    private fun url(path: String): HttpUrl =
        HttpUrl.Builder().scheme("http").host(base.host).port(base.port).addPathSegments(path).build()

    private fun get(path: String): Request.Builder = Request.Builder().url(url(path)).get()

    /** Per-call facts for the error mapping (plan §3.4). */
    internal class CallTrace(val body: StreamingBody?) {
        @Volatile var connected = false
    }

    private suspend fun <T> call(
        client: OkHttpClient,
        builder: Request.Builder,
        callTimeoutMs: Long,
        body: StreamingBody? = null,
        read: (Response) -> T,
    ): T {
        val trace = CallTrace(body)
        val c: Call = client.newCall(builder.tag(CallTrace::class.java, trace).build())
        if (callTimeoutMs > 0) c.timeout().timeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { c.cancel() }
            c.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(mapFailure(e, trace))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { Result.success(read(it)) }
                    } catch (e: IOException) {
                        Result.failure(mapFailure(e, trace))
                    } catch (e: IllegalArgumentException) { // incl. kotlinx SerializationException
                        Result.failure(FerryException.BadReply(e.message ?: e.toString()))
                    } catch (e: Throwable) {
                        Result.failure(e)
                    }
                    result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                }
            })
        }
    }

    private fun <T> decode(resp: Response, serializer: KSerializer<T>): T {
        if (resp.code != 200) throw httpError(resp)
        val text = resp.body?.string() ?: throw FerryException.BadReply("no body")
        return try {
            FerryJson.decodeFromString(serializer, text)
        } catch (e: IllegalArgumentException) {
            throw FerryException.BadReply("HTTP 200 but ${e.message?.take(200)}")
        }
    }

    private fun httpError(resp: Response): FerryException {
        if (resp.code == 507) return FerryException.DiskFull()
        val msg = try {
            resp.body?.string()?.let { FerryJson.decodeFromString(ErrorReply.serializer(), it).message }
        } catch (e: Exception) {
            null
        }
        return FerryException.Http(resp.code, msg)
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Plan §3.4 mapping. */
        internal fun mapFailure(e: IOException, trace: CallTrace): FerryException {
            var t: Throwable? = e
            while (t != null) { // SourceChanged thrown inside writeTo may come back wrapped [PB1]
                if (t is FerryException) return t
                t = t.cause
            }
            val body = trace.body
            val phase = when {
                !trace.connected -> "connect"
                body != null && !body.bodyFullySent -> "write"
                else -> "response"
            }
            return when {
                e is ConnectException || e is NoRouteToHostException || e is UnknownHostException ||
                    e is PortUnreachableException -> FerryException.Unreachable(e)
                e is SocketTimeoutException -> FerryException.Timeout(phase, e)
                e is InterruptedIOException && e.message == "timeout" -> FerryException.Timeout(phase, e)
                e is SocketException && isNoNetwork(e) -> FerryException.Unreachable(e)
                !trace.connected -> FerryException.Unreachable(e)
                else -> FerryException.ConnectionLost(body?.bodyFullySent ?: false, body?.bytesSent ?: 0L, e)
            }
        }

        private fun isNoNetwork(e: SocketException): Boolean {
            val m = e.message ?: return false
            return m.contains("ENETUNREACH") || m.contains("ENONET") || m.contains("Network is unreachable") ||
                m.contains("EHOSTUNREACH")
        }
    }
}
