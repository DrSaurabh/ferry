package app.ferry.core.cast

import app.ferry.core.FerryJson
import app.ferry.core.HostPort
import app.ferry.core.model.CastRequest
import app.ferry.core.model.CastState
import app.ferry.core.model.ControlRequest
import app.ferry.core.model.ErrorReply
import app.ferry.core.net.FerryException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Phone → TV (design §6): cast / control / state against the TV's [CastReceiverServer], 10 s call timeout
 * each [PB11]. [http] is the shared Wi-Fi-bound client [A9]. Failures are [FerryException]s: Unreachable
 * (connect failed), Timeout, ConnectionLost, Http (409 = the TV refused), BadReply.
 */
class CastClient(private val http: OkHttpClient, private val tv: HostPort) {
    suspend fun cast(req: CastRequest) {
        post(CastReceiverServer.CAST, FerryJson.encodeToString(CastRequest.serializer(), req))
    }

    suspend fun control(c: ControlRequest) {
        post(CastReceiverServer.CONTROL, FerryJson.encodeToString(ControlRequest.serializer(), c))
    }

    suspend fun state(): CastState {
        val req = Request.Builder().url(url(CastReceiverServer.STATE)).get().build()
        return call(req) { r ->
            val text = r.body?.string() ?: throw FerryException.BadReply("no body")
            try {
                FerryJson.decodeFromString(CastState.serializer(), text)
            } catch (e: IllegalArgumentException) {
                throw FerryException.BadReply("state: ${e.message?.take(200)}")
            }
        }
    }

    /**
     * This phone's IP as the TV sees it (design §6): the local address of a TCP connection to the TV, opened
     * with [http]'s socket factory (so on the Wi-Fi network [A9]) and closed at once. Suspends (network I/O);
     * throws [FerryException.Unreachable] if the TV cannot be reached within 3 s.
     */
    suspend fun localAddressTowardsTv(): String = withContext(Dispatchers.IO) {
        try {
            http.socketFactory.createSocket().use { s ->
                s.connect(InetSocketAddress(tv.host, tv.port), 3_000)
                s.localAddress.hostAddress ?: throw IOException("no local address")
            }
        } catch (e: IOException) {
            throw FerryException.Unreachable(e)
        }
    }

    private suspend fun post(path: String, json: String) {
        call(Request.Builder().url(url(path)).post(json.toRequestBody(JSON)).build()) { }
    }

    private fun url(path: String) = "http://${tv.host}:${tv.port}$path"

    private suspend fun <T> call(request: Request, read: (Response) -> T): T {
        val c: Call = http.newCall(request)
        c.timeout().timeout(10, TimeUnit.SECONDS)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { c.cancel() }
            c.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(map(e))

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            if (it.code != 200) throw httpError(it)
                            Result.success(read(it))
                        }
                    } catch (e: FerryException) {
                        Result.failure(e)
                    } catch (e: IOException) {
                        Result.failure(map(e))
                    } catch (e: Throwable) {
                        Result.failure(e)
                    }
                    result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                }
            })
        }
    }

    private fun httpError(r: Response): FerryException {
        val msg = try {
            r.body?.string()?.let { FerryJson.decodeFromString(ErrorReply.serializer(), it).message }
        } catch (e: Exception) {
            null
        }
        return FerryException.Http(r.code, msg)
    }

    private fun map(e: IOException): FerryException = when {
        e is FerryException -> e
        e is ConnectException || e is NoRouteToHostException || e is UnknownHostException -> FerryException.Unreachable(e)
        e is SocketTimeoutException || (e is InterruptedIOException && e.message == "timeout") ->
            FerryException.Timeout("response", e)
        else -> FerryException.ConnectionLost(false, 0, e)
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
