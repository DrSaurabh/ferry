package app.ferry

import android.app.Application
import android.os.Build
import android.provider.Settings
import android.util.Log
import app.ferry.cast.Casting
import app.ferry.core.SystemClock
import app.ferry.core.log.RingLog
import app.ferry.data.Prefs
import app.ferry.data.ResumeStore
import app.ferry.media.MediaRepo
import app.ferry.media.UploadItemFactory
import coil.ImageLoader
import app.ferry.net.ConnectionManager
import app.ferry.net.NetworkBinder
import app.ferry.net.UpdateState
import app.ferry.service.Downloads
import app.ferry.service.Transfers
import app.ferry.core.model.OutboxList
import app.ferry.install.AppUpdater
import app.ferry.media.DownloadSinks
import kotlinx.coroutines.CancellationException
import java.io.IOException
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

    /** Outbox downloads (Ph7), driven by TransferService (`ACTION_DOWNLOAD`). */
    lateinit var downloads: Downloads
        private set

    /** Phone → TV casting (Ph9): the session, driven by CastService. */
    lateinit var casting: Casting
        private set

    /** This process runs the TV's cast receiver (TV mode, or the debug loopback on a phone). */
    @Volatile var castReceiverOn = false

    /** The app update download (Ph7) [C3]. */
    lateinit var updater: AppUpdater
        private set

    /** The PC's outbox as last listed (From PC screen, Home badge). */
    val outbox = MutableStateFlow<OutboxList?>(null)

    /** Files left unsent when the previous process died mid-transfer [A8]; 0 = no banner. */
    val interrupted = MutableStateFlow(0)

    /**
     * Startup cleanup (plan §4.2 [PB16]): Ferry's own unfinished `Download/Ferry` rows [B7], `files/apk` (TV APKs
     * [PB18]) and `files/update`; downloads await it before creating their first sink.
     */
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
        downloads = Downloads(
            api = { connection.api() },
            waitForPc = { connection.relocate() },
            sinkFor = { f -> (if (isTv) DownloadSinks.forApk(app) else DownloadSinks.forPhone(app))(f) }, // TV: APKs only [PB18]
            log = { log("download", it) },
        )
        casting = Casting { log("cast", it) }
        updater = AppUpdater(scope, File(app.filesDir, "update"), { connection.api() }, { connection.relocate() }) { log("update", it) }
        interrupted.value = prefs.interruptedCount
        network.start()
        cleanup = scope.async(Dispatchers.IO) {
            DownloadSinks.cleanupLeftovers(app)
            File(app.filesDir, "apk").listFiles()?.forEach { it.delete() } // TV APK downloads [PB18]
            File(app.filesDir, "update").listFiles()?.forEach { it.delete() } // an older update APK
            Unit
        }
        log("app", "app start ${BuildConfig.VERSION_NAME} ${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}" +
            if (isTv) " (TV)" else "")
    }

    /** TV video positions (Ph8b, design §5.2). */
    val resume: ResumeStore by lazy { ResumeStore(app) }

    /** Picker thumbnails (Coil + MediaStore thumbnails, plan §4.7); created on first use. */
    val thumbnails: ImageLoader by lazy { MediaRepo.thumbnailLoader(app) }

    fun httpClient(): OkHttpClient = network.httpClient()

    /** Lists the PC's outbox into [outbox]; false if there is no PC or the request failed. */
    suspend fun refreshOutbox(): Boolean {
        val a = connection.api() ?: return false
        return try {
            outbox.value = a.outbox()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            log("outbox", "list failed: $e")
            connection.reportFailure()
            false
        }
    }

    fun log(tag: String, msg: String) {
        Log.i("Ferry", "$tag: $msg")
        if (this::ringLog.isInitialized) ringLog.log(tag, msg)
    }
}
