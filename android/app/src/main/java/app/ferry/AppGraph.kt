package app.ferry

import android.app.Application
import android.os.Build
import android.util.Log
import app.ferry.cast.Casting
import app.ferry.core.SystemClock
import app.ferry.core.log.Detail
import app.ferry.core.log.EventLog
import app.ferry.core.log.LogReader
import app.ferry.core.log.LogStateStore
import app.ferry.log.DeviceNames
import app.ferry.log.LogShipper
import app.ferry.log.SendEvents
import app.ferry.data.Prefs
import app.ferry.data.ResumeStore
import app.ferry.data.StaleFiles
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
    /** The 2.1 event log (design §7.1, CP108): every `log()`, `event()` and `detailed()` line goes here. */
    lateinit var events: EventLog
        private set

    /** 2.0 name kept for the androidTests that read `ringLog.snapshot()` [PS3]: the event log's in-memory tail. */
    val ringLog: EventLog get() = events

    /** Sends the event log to the PC (CP137). */
    lateinit var shipper: LogShipper
        private set

    /** This phone's/TV's name chain (M25-C15). */
    lateinit var names: DeviceNames
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
     * Startup cleanup (plan §4.2 [PB16]): Ferry's own unfinished `Download/Ferry` rows [B7], and files older than an
     * hour in `files/apk` (TV APKs [PB18]) and `files/update` ([StaleFiles], audit S5); downloads await it before
     * creating their first sink.
     */
    lateinit var cleanup: Deferred<Unit>
        private set

    val update = MutableStateFlow<UpdateState>(UpdateState.None)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** TV or phone layout; MainActivity may change it (debug override) before anything is shown. */
    @Volatile var isTv = false

    /** `files/logs/` (FileProvider `logs/` for "Share diagnostics log" [A14]): `ferry-app-log.txt` + `.1.txt`. */
    val logDir: File get() = File(app.filesDir, "logs")

    val logFile: File get() = events.file

    /** "Saurabh's vivo X200 Pro": the user's name for it, the system's, else maker + model [B10, H76, M25-C15]. */
    val deviceName: String get() = names.name

    /** `clock peer=pc offset_ms=` (M18), written when the offset moved by more than a second. */
    @Volatile private var lastOffset: Long? = null

    fun pcTime(pcMs: Long) {
        val off = pcMs - System.currentTimeMillis()
        val last = lastOffset
        if (last == null || Math.abs(off - last) > 1_000) {
            lastOffset = off
            event("clock", "peer" to "pc", "offset_ms" to off)
        }
    }

    fun init(app: Application) {
        this.app = app
        prefs = Prefs(app)
        val dir = logDir
        events = EventLog(dir, SystemClock, LogStateStore(File(dir, EventLog.STATE_FILE_NAME)), {
            if (prefs.logDetailed) Detail.Detailed else Detail.Normal
        })
        isTv = DeviceMode.isTv(app, null)
        event(
            "app.start", "ver" to BuildConfig.VERSION_NAME, "api" to Build.VERSION.SDK_INT,
            "tz" to EventLog.tzOffset(java.util.TimeZone.getDefault(), System.currentTimeMillis()),
            "exit" to (ExitReason.last(app) ?: "unknown"), "layout" to (if (isTv) "tv" else "phone"),
            "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
        )
        names = DeviceNames(app, { isTv }, { prefs.deviceNameOverride }, { prefs.installId }) { n, src ->
            event("device_name", "name" to n, "src" to src)
        }
        names.refresh()
        network = NetworkBinder(app, { deviceName }, BuildConfig.VERSION_NAME, { prefs.installId }, { if (isTv) "tv" else "phone" }) {
            log("net", it)
        }
        connection = ConnectionManager(
            scope = scope,
            network = network,
            store = prefs,
            self = { deviceName to (if (isTv) "tv" else "phone") },
            installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
            update = update,
            log = { log("conn", it) },
            onLocate = { result, ms, changed ->
                detailed("disc", "target" to "pc", "result" to result, "ms" to ms)
                if (changed) event("conn", "peer" to "pc", "result" to result)
            },
            onConnected = { info ->
                info.timeMs?.let(::pcTime)
                shipper.afterPcCall()
            },
        )
        transfers = Transfers(
            api = { connection.api() },
            rediscover = { connection.relocate() },
            sourceFor = { UploadItemFactory(app, { 0L }).sourceFor(it) },
            log = { log("engine", it) },
            newSendEvents = { SendEvents(events, System::currentTimeMillis, onEnded = { shipper.afterSend() }) },
            pcName = { (connection.state.value as? app.ferry.net.ConnectionState.Connected)?.name },
        )
        downloads = Downloads(
            api = { connection.api() },
            waitForPc = { connection.relocate() },
            sinkFor = { f -> (if (isTv) DownloadSinks.forApk(app) else DownloadSinks.forPhone(app))(f) }, // TV: APKs only [PB18]
            log = { log("download", it) },
        )
        casting = Casting { log("cast", it) }
        updater = AppUpdater(scope, File(app.filesDir, "update"), { connection.api() }, { connection.relocate() }) { log("update", it) }
        shipper = LogShipper(scope, events, LogReader(dir), { connection.api() }, SystemClock, ::pcTime, { log("logs", it) })
        interrupted.value = prefs.interruptedCount
        network.start()
        cleanup = scope.async(Dispatchers.IO) {
            DownloadSinks.cleanupLeftovers(app)
            // By age (audit S5): an APK handed to the installer just before this process start is left alone.
            val now = System.currentTimeMillis()
            StaleFiles.deleteOld(File(app.filesDir, "apk"), now) // TV APK downloads [PB18]
            StaleFiles.deleteOld(File(app.filesDir, "update"), now) // an older update APK
            Unit
        }
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
            shipper.afterPcCall()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            log("outbox", "list failed: $e")
            connection.reportFailure()
            false
        }
    }

    /** The 2.0 shim (CP108): `diag tag=<tag> msg="…"`, shown in the tail as `diag <tag> <msg>` (B107). */
    fun log(tag: String, msg: String) {
        Log.i("Ferry", "$tag: $msg")
        if (this::events.isInitialized) events.event("diag", "tag" to tag, "msg" to msg)
    }

    /** A 2.1 event (catalogue `docs/v2.1-log-events.md` part 1; `EventCatalogueTest`). */
    fun event(name: String, vararg kv: Pair<String, Any?>) {
        if (this::events.isInitialized) events.event(name, *kv)
    }

    /** A Detailed-only event (dropped when "Trial logging" is off, L2). */
    fun detailed(name: String, vararg kv: Pair<String, Any?>) {
        if (this::events.isInitialized) events.detailed(name, *kv)
    }
}
