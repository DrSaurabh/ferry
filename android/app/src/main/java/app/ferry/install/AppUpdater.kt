package app.ferry.install

import app.ferry.core.model.AppInfo
import app.ferry.core.net.FerryApi
import app.ferry.core.update.AppUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Where the app update stands (the banner shows it). */
sealed interface UpdateProgress {
    object Idle : UpdateProgress
    data class Downloading(val got: Long, val total: Long) : UpdateProgress
    /** Downloaded, size and sha256 verified (`:core` AppUpdate), ready for the installer. */
    data class Ready(val file: File, val info: AppInfo) : UpdateProgress
    object Failed : UpdateProgress
}

/**
 * The app update download (design §4.6, plan §4.8) [C3]: `GET` the PC's APK into `files/update/Ferry.apk`, checked
 * for size and sha256 while it is written; the banner then hands it to [ApkInstaller]. One download at a time; a
 * verified file of the same version is not fetched again.
 */
class AppUpdater(
    private val scope: CoroutineScope,
    private val dir: File,
    private val api: () -> FerryApi?,
    private val relocate: suspend () -> FerryApi?,
    private val log: (String) -> Unit,
) {
    private val _progress = MutableStateFlow<UpdateProgress>(UpdateProgress.Idle)
    val progress: StateFlow<UpdateProgress> = _progress
    private var job: Job? = null

    val target: File get() = File(dir, "Ferry.apk")

    fun start(info: AppInfo) {
        synchronized(this) {
            if (job?.isActive == true) return
            val now = _progress.value
            if (now is UpdateProgress.Ready && now.info == info && now.file.length() == info.size) return
            _progress.value = UpdateProgress.Downloading(0, info.size)
            job = scope.launch { download(info) }
        }
    }

    private suspend fun download(info: AppInfo) {
        try {
            val a = api() ?: relocate() ?: throw java.io.IOException("PC not found")
            log("update: downloading ${info.versionName} (${info.size} bytes)")
            var shown = -1L
            val f = AppUpdate.downloadApk(a, info, target) { got ->
                val pct = if (info.size > 0) got * 100 / info.size else 100
                if (pct != shown) {
                    shown = pct
                    _progress.value = UpdateProgress.Downloading(got, info.size)
                }
            }
            log("update: ${info.versionName} downloaded, size and sha256 OK")
            _progress.value = UpdateProgress.Ready(f, info)
        } catch (e: CancellationException) {
            _progress.value = UpdateProgress.Idle
            throw e
        } catch (e: Exception) {
            log("update: download failed: $e")
            target.delete()
            _progress.value = UpdateProgress.Failed
        }
    }
}
