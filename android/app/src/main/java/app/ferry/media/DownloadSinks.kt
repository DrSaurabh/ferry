package app.ferry.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import app.ferry.AppGraph
import app.ferry.core.download.DownloadSink
import app.ferry.core.download.FileSink
import app.ferry.core.model.OutFile
import app.ferry.core.net.FerryException
import app.ferry.install.ApkInstaller
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Where outbox downloads go (design §4.5, plan §4.7) [A16] [B7]:
 * - phone, API 29+: a new MediaStore Downloads entry in `Download/Ferry` with `IS_PENDING = 1`, published only when
 *   complete (MediaStore picks "name (1).ext" on a clash, so nothing is ever overwritten); deleted on any failure;
 * - TV APKs [PB18]: `filesDir/apk/<outbox id>.apk` (the PC's name is never used as a path), Ph8a wires the dialog;
 * - API 25–28: no download sink at all [CP17].
 * Every sink is created only after the start-up cleanup has finished [PB16].
 */
object DownloadSinks {
    const val RELATIVE_PATH = "Download/Ferry"
    private const val MARGIN = 50L * 1000 * 1000 // free-space margin [A16]

    /** Downloads to `Download/Ferry` exist from Android 10 (API 29) [CP17]. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 29

    /** The queue's sink factory on the phone (runs on the queue's IO coroutine). */
    fun forPhone(ctx: Context): (OutFile) -> DownloadSink = { f ->
        awaitCleanup()
        checkSpace(publicDownloads(), f.size)
        if (Build.VERSION.SDK_INT >= 29) MediaStoreDownloadSink(ctx.applicationContext, f.name, mimeFor(f.name))
        else throw IOException("downloads need API 29") // never offered in the UI [CP17]
    }

    /** A TV/outbox APK into Ferry's own folder (`filesDir/apk/<id>.apk`) [PB18]. */
    fun apkFile(ctx: Context, f: OutFile): File = File(File(ctx.filesDir, "apk"), "${f.id}.apk")

    fun forApk(ctx: Context): (OutFile) -> DownloadSink = { f ->
        awaitCleanup()
        checkSpace(ctx.filesDir, f.size)
        FileSink(apkFile(ctx, f))
    }

    private fun awaitCleanup() {
        val c = AppGraph.cleanup
        if (!c.isCompleted) runBlocking { c.await() } // short, on the queue's IO thread [PB16]
    }

    @Suppress("DEPRECATION")
    private fun publicDownloads(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /** `StatFs(dir).availableBytes < size + 50 MB` → NotEnoughSpace [A16]. */
    fun checkSpace(dir: File, size: Long) {
        val free = try {
            StatFs(if (dir.exists()) dir.path else Environment.getExternalStorageDirectory().path).availableBytes
        } catch (e: IllegalArgumentException) {
            return // can't tell: the write itself still reports a full disk (B58)
        }
        if (free < size + MARGIN) throw FerryException.NotEnoughSpace(size + MARGIN, free)
    }

    fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "apk") return ApkInstaller.APK_MIME
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /**
     * Deletes Ferry's own leftover `IS_PENDING = 1` rows in `Download/Ferry` (a download cut off by a process death)
     * [B7]. Pending rows are only visible with `QUERY_ARG_MATCH_PENDING` (API 30+) or `setIncludePending` (API 29)
     * [PR4]. Returns how many were deleted.
     */
    fun cleanupLeftovers(ctx: Context): Int = if (Build.VERSION.SDK_INT >= 29) cleanupPending(ctx) else 0

    @RequiresApi(29)
    private fun cleanupPending(ctx: Context): Int {
        val cr = ctx.contentResolver
        val ids = try {
            pendingIds(cr)
        } catch (e: Exception) {
            AppGraph.log("download", "leftover query failed: $e")
            return 0
        }
        var n = 0
        for (id in ids) {
            try {
                n += cr.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null)
            } catch (e: Exception) {
                AppGraph.log("download", "leftover $id not deleted: $e")
            }
        }
        if (n > 0) AppGraph.log("download", "deleted $n unfinished download(s) in $RELATIVE_PATH")
        return n
    }

    @RequiresApi(29)
    private fun pendingIds(cr: ContentResolver): List<Long> {
        val sel = "${MediaStore.MediaColumns.IS_PENDING} = 1 AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("$RELATIVE_PATH/%")
        val proj = arrayOf(MediaStore.MediaColumns._ID)
        val c = if (Build.VERSION.SDK_INT >= 30) {
            cr.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj,
                Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                },
                null,
            )
        } else {
            @Suppress("DEPRECATION")
            cr.query(MediaStore.setIncludePending(MediaStore.Downloads.EXTERNAL_CONTENT_URI), proj, sel, args, null)
        }
        val ids = mutableListOf<Long>()
        c?.use { while (it.moveToNext()) ids += it.getLong(0) }
        return ids
    }
}

/**
 * One download into a fresh MediaStore Downloads row (API 29+, design §4.5): inserted with `IS_PENDING = 1` in
 * `Download/Ferry`; the stream syncs the file in `close()`; [commit] (only after close [PB17]) publishes it and
 * returns the name MediaStore chose ("report (1).pdf" on a clash); [abort] deletes the row and its file [B7].
 */
@RequiresApi(29)
class MediaStoreDownloadSink(private val ctx: Context, private val name: String, private val mime: String) : DownloadSink {
    private var uri: Uri? = null

    override fun open(): OutputStream {
        val cr = ctx.contentResolver
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, DownloadSinks.RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw IOException("MediaStore refused the new download")
        uri = u
        val pfd = cr.openFileDescriptor(u, "w") ?: throw IOException("cannot open $u")
        val out = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
        return object : OutputStream() {
            private var closed = false
            override fun write(b: Int) = out.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun flush() = out.flush()
            override fun close() {
                if (closed) return
                closed = true
                try {
                    out.flush()
                    out.fd.sync()
                } finally {
                    out.close()
                }
            }
        }
    }

    override fun commit(): String {
        val u = uri ?: throw IOException("nothing to publish")
        val cr = ctx.contentResolver
        val n = cr.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        if (n != 1) throw IOException("publish of $u changed $n rows")
        return cr.query(u, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: name
    }

    override fun abort() {
        val u = uri ?: return
        uri = null
        try {
            ctx.contentResolver.delete(u, null, null)
        } catch (e: Exception) {
            AppGraph.log("download", "could not delete unfinished $u: $e") // the start-up cleanup retries [B7]
        }
    }

    override fun openUri(): String = uri?.toString().orEmpty()
}
