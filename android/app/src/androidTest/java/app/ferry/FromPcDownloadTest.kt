package app.ferry

import android.app.Instrumentation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ferry.core.HostPort
import app.ferry.core.download.DownloadStatus
import app.ferry.media.DownloadSinks
import app.ferry.net.ConnectionState
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * Phone suite class 7 (plan §4.14) [P7] [A16]: From PC lists PC-B's outbox; tapping `report.pdf` downloads it into a
 * MediaStore row in `Download/Ferry` with the same bytes as a direct GET; downloading it again gives
 * `report (1).pdf` and leaves the first file unchanged (never overwrite). Also: the start-up cleanup deletes Ferry's
 * own pending rows [B7] (API 30+ `QUERY_ARG_MATCH_PENDING` [PR4]), and **Open** hands the file to the system with a
 * read grant. No Compose test rule (the download runs in the service, B50): UiAutomator + app state.
 * The host clears `/sdcard/Download/Ferry` before this class.
 */
@RunWith(AndroidJUnit4::class)
@RequiresApi(30)
class FromPcDownloadTest {
    private val ctx get() = Probe.context
    private val cr get() = ctx.contentResolver

    /** Records activity starts and blocks them (no PDF viewer is needed): API 26+ `onStartActivity`. */
    private class Recorder : Instrumentation.ActivityMonitor() {
        val intents: MutableList<Intent> = Collections.synchronizedList(mutableListOf())
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != Intent.ACTION_VIEW) return null
            intents += Intent(intent)
            return Instrumentation.ActivityResult(0, null)
        }
    }

    private fun bytes(uri: String): ByteArray = cr.openInputStream(Uri.parse(uri))!!.use { it.readBytes() }

    private fun rows(): List<String> {
        val out = mutableListOf<String>()
        cr.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING),
            Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("${DownloadSinks.RELATIVE_PATH}/%"))
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            },
            null,
        )?.use { c -> while (c.moveToNext()) out += c.getString(0) + if (c.getInt(1) == 1) " (pending)" else "" }
        return out.sorted()
    }

    private fun status(id: Int): DownloadStatus? = AppGraph.downloads.state.value[id]

    @Test
    fun downloadTwiceNeverOverwrites() {
        LaunchHelpers.cleanPrefs(welcomeDone = true)
        AppGraph.prefs.manual = HostPort.parse(SendHelpers.pcB)

        // Start-up cleanup [B7] [PR4]: a pending row of Ferry's own (a download cut off by a process death) is deleted.
        val leftover = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "leftover-test.bin")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, DownloadSinks.RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })!!
        Probe.record("leftover_before", rows())
        val deleted = DownloadSinks.cleanupLeftovers(ctx)
        Probe.record("leftover_cleanup", "deleted=$deleted rows_after=${rows()}")
        assertTrue("cleanup deleted $deleted rows", deleted >= 1)
        assertTrue("leftover still there: ${rows()}", rows().none { it.startsWith("leftover-test") })
        Probe.record("leftover_uri", "$leftover id=${ContentUris.parseId(leftover)}")

        val rec = Recorder()
        Probe.instrumentation.addMonitor(rec)
        try {
            LaunchHelpers.launch(open = MainActivity.OPEN_FROM_PC).use {
                SendHelpers.waitConnected()
                val pc = AppGraph.connection.state.value as ConnectionState.Connected
                assertEquals(SendHelpers.pcB, pc.api.base.toString())
                SendHelpers.waitText("report.pdf", 30_000)
                val f = AppGraph.outbox.value!!.files.first { it.name == "report.pdf" }
                val direct = Probe.http.newCall(Request.Builder().url(pc.api.absoluteUrl(f.url)).build()).execute().use {
                    check(it.code == 200) { "direct GET → ${it.code}" }
                    it.body!!.bytes()
                }
                assertEquals(f.size, direct.size.toLong())
                SendHelpers.settle()
                Shots.take("from-pc-list")

                // 1st download
                SendHelpers.tap("report.pdf")
                SendHelpers.waitUntil("first download done", 60_000) { status(f.id) is DownloadStatus.Done }
                val d1 = status(f.id) as DownloadStatus.Done
                Probe.record("download_1", "${d1.savedAs} ${d1.openUri}")
                assertEquals("report.pdf", d1.savedAs)
                assertArrayEquals("first download bytes", direct, bytes(d1.openUri))
                SendHelpers.waitText("Saved in Download/Ferry as report.pdf", 10_000)
                SendHelpers.settle()
                Shots.take("from-pc-downloaded")

                // 2nd download of the same file: a new entry, the first is untouched
                SendHelpers.tap("report.pdf")
                SendHelpers.waitUntil("second download done", 60_000) {
                    val s = status(f.id)
                    s is DownloadStatus.Done && s.openUri != d1.openUri
                }
                val d2 = status(f.id) as DownloadStatus.Done
                Probe.record("download_2", "${d2.savedAs} ${d2.openUri}")
                assertEquals("report (1).pdf", d2.savedAs)
                assertArrayEquals("second download bytes", direct, bytes(d2.openUri))
                assertArrayEquals("first file unchanged", direct, bytes(d1.openUri))
                val names = rows()
                Probe.record("download_rows", names)
                assertEquals(listOf("report (1).pdf", "report.pdf"), names)
                // B82: the row's "Saved … as report (1).pdf" is proven from app state above (`savedAs`); UiAutomator's
                // copy of Compose text lagged twice (main run 75, Ph9 run 4) while the screen showed it, so it is only recorded.
                Probe.record("uiautomator_sees_saved_as", SendHelpers.device.hasObject(androidx.test.uiautomator.By.textContains("as report (1).pdf")))
                Thread.sleep(1_000)
                SendHelpers.settle()
                Shots.take("from-pc-downloaded-twice")

                // Open → the system viewer gets the content URI with a read grant [A16]
                SendHelpers.tap("Open")
                SendHelpers.waitUntil("ACTION_VIEW", 10_000) { rec.intents.isNotEmpty() }
                val v = rec.intents.first()
                Probe.record("open_intent", "${v.data} ${v.type} flags=0x${Integer.toHexString(v.flags)}")
                assertEquals(d2.openUri, v.data.toString())
                assertEquals("application/pdf", v.type)
                assertTrue(v.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            }
        } finally {
            Probe.instrumentation.removeMonitor(rec)
        }
    }
}
