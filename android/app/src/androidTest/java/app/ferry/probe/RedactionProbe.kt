package app.ferry.probe

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ph0b, phone image only (API 34): does Android redact GPS for an app without ACCESS_MEDIA_LOCATION
 * (design B1, plan §4.14)? The host pushed gps.jpg as the shell user and scanned it; this class runs
 * twice: withoutPermission (fresh install, only READ_MEDIA_IMAGES) and withPermission (after
 * `pm grant … ACCESS_MEDIA_LOCATION`). Instrumentation args: gpsName, gpsSha (sha256 of the fixture).
 */
@RunWith(AndroidJUnit4::class)
class RedactionProbe {
    private val cr get() = Probe.context.contentResolver
    private val name get() = Probe.arg("gpsName", "IMG_20250101_120000.jpg")
    private val fixtureSha get() = Probe.arg("gpsSha", "")

    private fun hasLocationPermission() =
        Probe.context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Polls MediaStore up to 60 s for the pushed file; returns its URI and OWNER_PACKAGE_NAME. */
    private fun findRow(): Pair<Uri, String?> {
        val deadline = System.currentTimeMillis() + 60_000
        val started = System.currentTimeMillis()
        while (true) {
            cr.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(name), null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    Probe.record("redaction_row_visible_after_ms", System.currentTimeMillis() - started)
                    return uri to c.getString(1)
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                Probe.record("redaction_row_missing", "visible incl. pending/trashed: ${diagnose()}")
                error("$name never appeared in MediaStore")
            }
            Thread.sleep(500)
        }
    }

    /** Is the row there but pending or trashed (hidden from other apps)? API 30+ query args. */
    private fun diagnose(): String = runCatching {
        val args = android.os.Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DISPLAY_NAME} = ?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(name))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED), args, null)?.use { c ->
            if (c.moveToFirst()) "yes (is_pending=${c.getInt(0)} is_trashed=${c.getInt(1)})" else "no"
        } ?: "null cursor"
    }.getOrElse { "query failed: $it" }

    private fun readPlain(uri: Uri): ByteArray = cr.openInputStream(uri)!!.use { it.readBytes() }

    private fun readOriginal(uri: Uri): Result<ByteArray> =
        runCatching { cr.openInputStream(MediaStore.setRequireOriginal(uri))!!.use { it.readBytes() } }

    @Test
    fun withoutPermission() {
        assertFalse("ACCESS_MEDIA_LOCATION must not be granted in run 1", hasLocationPermission())
        assertTrue("gpsSha argument missing", fixtureSha.isNotEmpty())
        val (uri, owner) = findRow()
        Probe.record("redaction_owner_package", owner)
        val plain = readPlain(uri)
        Probe.outFile("redacted.jpg").writeBytes(plain)
        val plainSha = Probe.sha256(plain)
        Probe.record("redaction_run1_plain_differs_from_fixture", plainSha != fixtureSha)
        Probe.record("redaction_run1_plain_size", "${plain.size}")
        val orig = readOriginal(uri)
        Probe.record("redaction_run1_require_original",
            orig.fold({ "opened (${it.size} bytes, identical=${Probe.sha256(it) == fixtureSha})" },
                { "failed ${it.javaClass.name}: ${it.message}" }))
        assertNotEquals("Ferry must not own the row (owners are never redacted)", "app.ferry", owner)
        assertNotEquals("redaction not observed: plain read returned the original bytes", fixtureSha, plainSha)
        assertTrue("setRequireOriginal open should fail without ACCESS_MEDIA_LOCATION", orig.isFailure)
    }

    @Test
    fun withPermission() {
        assertTrue("ACCESS_MEDIA_LOCATION must be granted in run 2", hasLocationPermission())
        val (uri, owner) = findRow()
        val plain = readPlain(uri)
        Probe.record("redaction_run2_plain_identical", Probe.sha256(plain) == fixtureSha)
        val orig = readOriginal(uri).getOrThrow()
        val same = Probe.sha256(orig) == fixtureSha
        Probe.record("redaction_run2_require_original_identical", same)
        Probe.record("redaction_run2_owner_package", owner)
        assertEquals("original bytes must equal the fixture", fixtureSha, Probe.sha256(orig))
    }
}
