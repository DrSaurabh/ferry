package app.ferry.probe

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Ph0b probe helpers (throw-away; deleted when the real instrumented tests exist).
 * Results go to logcat (tag FERRY_PROBE) and to probe/results.txt under [baseDir], which
 * run_instrumented.sh pulls and prints.
 */
object Probe {
    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context get() = instrumentation.targetContext

    fun arg(name: String, default: String): String =
        InstrumentationRegistry.getArguments().getString(name) ?: default

    val pc: String get() = arg("pcA", "10.0.2.2:18765")

    /**
     * Where screenshots and probe files go [CP16]: the external files dir (host: `adb pull`), or with
     * `-e store internal` filesDir (host: `run-as app.ferry tar`), for images where the shell may not
     * read Android/data (API 30 TV, Ph0b).
     */
    val baseDir: File
        get() = if (arg("store", "external") == "internal") context.filesDir else context.getExternalFilesDir(null)!!

    fun record(key: String, value: Any?) {
        val line = "$key=$value"
        Log.i("FERRY_PROBE", line)
        val dir = File(baseDir, "probe").apply { mkdirs() }
        File(dir, "results.txt").appendText(line + "\n")
    }

    fun outFile(name: String): File =
        File(File(baseDir, "probe").apply { mkdirs() }, name)

    /** The product's timeouts (design §5.2 [B13]): connect 8 s, read 20 s. */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Ferry-Device", "CI probe").build())
            }
            .build()
    }

    fun get(path: String): String =
        http.newCall(Request.Builder().url("http://$pc$path").build()).execute().use { r ->
            check(r.code == 200) { "GET $path → HTTP ${r.code}" }
            r.body!!.string()
        }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
