package app.ferry.core.net

import app.ferry.core.util.PercentEncode
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** Builds the shared OkHttpClient (plan §3.2, design §3.5, §4.4). */
object FerryHttp {
    const val DEVICE_HEADER = "X-Ferry-Device"

    /**
     * connect 5 s, write 60 s, read 30 s [PB11], no silent retries. Every request carries `X-Ferry-Device`
     * (percent-encoded UTF-8 [B10]) and `User-Agent: Ferry-Android/<appVersion>` [CP15]. [socketFactory] is the
     * Wi-Fi network's factory on Android [A9] (null = default).
     */
    fun newClient(socketFactory: SocketFactory?, deviceName: String, appVersion: String = "dev"): OkHttpClient {
        val device = PercentEncode.utf8(deviceName)
        val agent = "Ferry-Android/$appVersion"
        val b = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header(DEVICE_HEADER, device)
                        .header("User-Agent", agent)
                        .build(),
                )
            }
        if (socketFactory != null) b.socketFactory(socketFactory)
        return b.build()
    }
}
