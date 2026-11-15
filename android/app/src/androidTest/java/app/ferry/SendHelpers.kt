package app.ferry

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.ComposeTestRule
import app.ferry.core.upload.EngineState
import app.ferry.net.ConnectionState
import app.ferry.probe.Probe

/** Shared steps of the sending tests (plan §4.14): media rows made by the shell, PC addresses, engine waits. */
object SendHelpers {
    class MediaRow(val id: Long, val uri: Uri, val owner: String?, val dateModifiedS: Long)

    val pcB: String get() = Probe.arg("pcB", "10.0.2.2:18766")
    val gpsName: String get() = Probe.arg("gpsName", "IMG_20250101_120000.jpg")

    fun hasLocationPermission() =
        Probe.context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The image the host pushed and scanned as the shell user (polls up to 60 s). */
    fun findImage(name: String, timeoutMs: Long = 60_000): MediaRow {
        val cr = Probe.context.contentResolver
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            cr.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.DATE_MODIFIED),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(name), null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    return MediaRow(id, ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), c.getString(1), c.getLong(2))
                }
            }
            check(System.currentTimeMillis() < deadline) { "$name never appeared in MediaStore" }
            Thread.sleep(500)
        }
    }

    fun waitConnected(rule: ComposeTestRule) =
        LaunchHelpers.waitFor("connected", rule, 30_000) { AppGraph.connection.state.value is ConnectionState.Connected }

    /** Waits on the published engine state; on timeout the state, RingLog and a screenshot go to the job log. */
    fun waitTransfer(rule: ComposeTestRule, what: String, timeoutMs: Long = 60_000, cond: (EngineState?) -> Boolean) {
        try {
            LaunchHelpers.waitFor(what, rule, timeoutMs) { cond(AppGraph.transfers.state.value) }
        } finally {
            Probe.record("transfer_state", AppGraph.transfers.state.value?.let { "${it.phase} ${it.items.map { r -> r.status }} ${it.summary}" })
        }
    }

    /**
     * Taps a button by its text through UiAutomator (real input events). Ph5a B48: Compose-test `performClick`
     * during a transfer's state switch twice crashed the app with "multithreaded access to SnapshotStateObserver".
     */
    fun tap(text: String, timeoutMs: Long = 10_000) {
        val dev = androidx.test.uiautomator.UiDevice.getInstance(Probe.instrumentation)
        val o = dev.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text(text)), timeoutMs)
        checkNotNull(o) { "no '$text' on screen" }.click()
    }

    /** Engine runs that have finished so far (each logs "finished: Summary(…)"), to wait for the next one. */
    fun finishedRuns(): Int = AppGraph.ringLog.snapshot().count { it.contains(" engine finished:") }
}
