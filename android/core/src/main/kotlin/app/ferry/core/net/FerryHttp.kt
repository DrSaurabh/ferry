package app.ferry.core.net

import app.ferry.core.util.PercentEncode
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** Builds the shared OkHttpClient (plan §3.2, design §3.5, §4.4). */
object FerryHttp {
    const val DEVICE_HEADER = "X-Ferry-Device"
    /** 2.1 (plan §2.3, CP126): 4 hex chars of the install id. */
    const val DEVICE_ID_HEADER = "X-Ferry-Device-Id"
    /** 2.1: `phone` or `tv` (M9/C6 on the PC). */
    const val DEVICE_TYPE_HEADER = "X-Ferry-Device-Type"
    const val LOG_FROM_HEADER = "X-Ferry-Log-From"

    /**
     * connect 5 s, write 60 s, read 30 s [PB11], no silent retries. Every request carries `X-Ferry-Device`
     * (percent-encoded UTF-8 [B10]) and `User-Agent: Ferry-Android/<appVersion>` [CP15]. [socketFactory] is the
     * Wi-Fi network's factory on Android [A9] (null = default).
     *
     * Every request also says `Connection: close` (Ph2a, B23): ferry.py is an HTTP/1.0 server and closes
     * the socket after each response, but OkHttp pools the connection unless it sees "close"; the next call
     * would then hit the dead socket ("Broken pipe") and, with retryOnConnectionFailure(false), fail.
     *
     * 2.1: [deviceId] / [deviceType] add `X-Ferry-Device-Id` / `X-Ferry-Device-Type` when given (null = left out).
     */
    fun newClient(
        socketFactory: SocketFactory?,
        deviceName: String,
        appVersion: String = "dev",
        deviceId: String? = null,
        deviceType: String? = null,
    ): OkHttpClient {
        val device = PercentEncode.utf8(deviceName)
        val agent = "Ferry-Android/$appVersion"
        val b = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                val r = chain.request().newBuilder()
                    .header(DEVICE_HEADER, device)
                    .header("User-Agent", agent)
                    .header("Connection", "close")
                if (deviceId != null) r.header(DEVICE_ID_HEADER, deviceId)
                if (deviceType != null) r.header(DEVICE_TYPE_HEADER, deviceType)
                chain.proceed(r.build())
            }
        if (socketFactory != null) b.socketFactory(socketFactory)
        return b.build()
    }
}
