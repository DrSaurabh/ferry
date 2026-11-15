package app.ferry

import android.app.Application
import android.os.Build
import android.provider.Settings
import android.util.Log
import app.ferry.core.SystemClock
import app.ferry.core.log.RingLog
import app.ferry.data.Prefs
import app.ferry.media.UploadItemFactory
import app.ferry.net.ConnectionManager
import app.ferry.net.NetworkBinder
import app.ferry.net.UpdateState
import app.ferry.service.Transfers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import java.io.File

/**
 * Manual DI (plan §1.3, design §3.4): the process-wide objects and state flows. Created once in
 * [FerryApp.onCreate]; every test class starts in a fresh process (the host force-stops the app, §4.14).
 */
object AppGraph {
    lateinit var app: Application
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var ringLog: RingLog
        private set
    lateinit var network: NetworkBinder
        private set
    lateinit var connection: ConnectionManager
        private set

    /** The upload queue (`AppGraph.transfer` of the plan is [Transfers.state]); driven by TransferService (Ph5a). */
    lateinit var transfers: Transfers
        private set

    /** Files left unsent when the previous process died mid-transfer [A8]; 0 = no banner. */
    val interrupted = MutableStateFlow(0)

    /** Startup cleanup (plan §4.2 [PB16]): downloads await it before creating their first sink (Ph7). */
    lateinit var cleanup: Deferred<Unit>
        private set

    val update = MutableStateFlow<UpdateState>(UpdateState.None)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** TV or phone layout; MainActivity may change it (debug override) before anything is shown. */
    @Volatile var isTv = false

    val logFile: File get() = File(app.filesDir, "logs/ferry-app-log.txt")

    /** "Saurabh's vivo X200 Pro" (Settings → device name), else the model. Shown in the PC's log [B10]. */
    val deviceName: String by lazy {
        val n = try { Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) } catch (e: Exception) { null }
        if (n.isNullOrBlank()) Build.MODEL ?: "Android" else n
    }

    fun init(app: Application) {
        this.app = app
        prefs = Prefs(app)
        ringLog = RingLog(logFile, SystemClock)
        isTv = DeviceMode.isTv(app, null)
        network = NetworkBinder(app, { deviceName }, BuildConfig.VERSION_NAME) { log("net", it) }
        connection = ConnectionManager(
            scope = scope,
            network = network,
            store = prefs,
            self = { deviceName to (if (isTv) "tv" else "phone") },
            installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
            update = update,
            log = { log("conn", it) },
        )
        transfers = Transfers(
            api = { connection.api() },
            rediscover = { connection.relocate() },
            sourceFor = { UploadItemFactory(app, { 0L }).sourceFor(it) },
            log = { log("engine", it) },
        )
        interrupted.value = prefs.interruptedCount
        network.start()
        cleanup = scope.async(Dispatchers.IO) {
            File(app.filesDir, "apk").listFiles()?.forEach { it.delete() } // TV APK downloads [PB18]
            Unit
        }
        log("app", "app start ${BuildConfig.VERSION_NAME} ${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}" +
            if (isTv) " (TV)" else "")
    }

    fun httpClient(): OkHttpClient = network.httpClient()

    fun log(tag: String, msg: String) {
        Log.i("Ferry", "$tag: $msg")
        if (this::ringLog.isInitialized) ringLog.log(tag, msg)
    }
}
