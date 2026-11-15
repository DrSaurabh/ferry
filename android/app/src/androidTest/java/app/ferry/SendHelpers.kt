package app.ferry

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import app.ferry.core.upload.EngineState
import app.ferry.net.ConnectionState
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

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

    private val device get() = UiDevice.getInstance(Probe.instrumentation)

    /**
     * Polling wait without a Compose test rule (Ph5a B50): the rule's test frame clock ran layout off the main thread
     * while the Transfer screen collected the engine's state from IO, crashing the app. On timeout the state, the
     * RingLog and a screenshot go to the job log.
     */
    fun waitUntil(what: String, timeoutMs: Long, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) {
                Probe.record("wait_failed", "$what; state=${AppGraph.connection.state.value}")
                for (line in AppGraph.ringLog.snapshot().takeLast(40)) Probe.record("ringlog", line)
                runCatching { Shots.take("fail-" + what.filter { it.isLetterOrDigit() }.take(30)) }
                throw AssertionError("timed out after $timeoutMs ms waiting for $what")
            }
            Thread.sleep(100)
        }
    }

    fun waitConnected() = waitUntil("connected", 30_000) { AppGraph.connection.state.value is ConnectionState.Connected }

    /** Text on screen (UiAutomator; substring). */
    fun waitText(text: String, timeoutMs: Long = 30_000) =
        waitUntil("text '$text'", timeoutMs) { device.hasObject(By.textContains(text)) }

    /** Lets the last frame reach the screen before a screenshot. */
    fun settle() {
        device.waitForIdle()
        Thread.sleep(700)
    }

    /** Waits on the published engine state. */
    fun waitTransfer(what: String, timeoutMs: Long = 60_000, cond: (EngineState?) -> Boolean) {
        try {
            waitUntil(what, timeoutMs) { cond(AppGraph.transfers.state.value) }
        } finally {
            Probe.record("transfer_state", AppGraph.transfers.state.value?.let { "${it.phase} ${it.items.map { r -> r.status }} ${it.summary}" })
        }
    }

    /**
     * Taps a button by its text through UiAutomator (real input events, no Compose test rule; B48/B50).
     */
    fun tap(text: String, timeoutMs: Long = 10_000) {
        val o = device.wait(Until.findObject(By.text(text)), timeoutMs)
        checkNotNull(o) { "no '$text' on screen" }.click()
    }

    /** Engine runs that have finished so far (each logs "finished: Summary(…)"), to wait for the next one. */
    fun finishedRuns(): Int = AppGraph.ringLog.snapshot().count { it.contains(" engine finished:") }
}
