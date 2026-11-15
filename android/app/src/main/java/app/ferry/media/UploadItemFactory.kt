package app.ferry.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import app.ferry.AppGraph
import app.ferry.core.upload.SourceMeta
import app.ferry.core.upload.UploadSource
import app.ferry.ui.common.PermissionPlanner
import java.io.File

/**
 * Builds [PreparedItem]s (design §4.3, plan §4.7). Runs only inside `SendCoordinator.prepare()` on `Dispatchers.IO`,
 * before the service starts [PB5].
 *
 * - MediaStore items: on API 29+ with "photo location" access the original is **probed by opening**
 *   `setRequireOriginal(uri)`; no permission or a failed open → the item needs the location choice [A2] [B6].
 *   Size = length of the opened descriptor, else `SIZE` [B5]; date = `DATE_MODIFIED` × 1000 only if > 0 [B17].
 * - Share / SAF URIs: media-document URIs are mapped with `MediaStore.getMediaUri` (29+, any exception = "not
 *   mapped" [PR3]); other providers by their `_data` path; unmapped images/videos raise the "may lose their
 *   location" note [A19]; size unknown → asset length → copy into `cacheDir/share/` [design §4.3].
 */
class UploadItemFactory(private val ctx: Context, private val newId: () -> Long) {
    private val cr: ContentResolver = ctx.contentResolver
    private val sdk = Build.VERSION.SDK_INT

    class One(val item: PreparedItem, val needsChoice: Boolean, val loseLocation: Boolean = false)

    fun hasLocationPermission(): Boolean {
        val p = PermissionPlanner.locationPermission(sdk) ?: return false
        return ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    fun fromPick(p: MediaPick, hasLocation: Boolean): One {
        val base = if (p.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return fromMedia(ContentUris.withAppendedId(base, p.id), hasLocation)
    }

    fun fromUri(uri: Uri, hasLocation: Boolean): One {
        if (isMedia(uri)) return fromMedia(uri, hasLocation)
        val mapped = mapToMedia(uri)
        if (mapped != null && openLength(mapped) != null) return fromMedia(mapped, hasLocation)
        val mime = try { cr.getType(uri) } catch (e: Exception) { null }
        val lose = sdk >= 29 && mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))
        val m = queryMeta(uri, media = false)
        val name = m?.name ?: uri.lastPathSegment ?: "file"
        val size = m?.size?.takeIf { it >= 0 } ?: assetLength(uri)
        if (size != null) {
            return One(PreparedItem(newId(), uri, name, size, m?.mtimeMs, requireOriginal = false), false, lose)
        }
        // Size unknown (some cloud providers): copy into the cache and send the copy.
        val id = newId()
        val copy = File(File(ctx.cacheDir, SHARE_CACHE).apply { mkdirs() }, "$id-" + name.replace(Regex("[/\\\\:]"), "_"))
        return try {
            cr.openInputStream(uri)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
            One(PreparedItem(id, Uri.fromFile(copy), name, copy.length(), m?.mtimeMs, requireOriginal = false), false, lose)
        } catch (e: Exception) {
            copy.delete()
            AppGraph.log("send", "can't read $uri: $e")
            One(PreparedItem(id, uri, name, 0, null, requireOriginal = false, unreadable = true), false)
        }
    }

    private fun fromMedia(uri: Uri, hasLocation: Boolean): One {
        val m = queryMeta(uri, media = true)
        var size: Long? = null
        var originalOpened = false
        if (Build.VERSION.SDK_INT >= 29 && hasLocation) {
            val s = openLength(MediaStore.setRequireOriginal(uri))
            if (s != null) {
                originalOpened = true
                if (s >= 0) size = s
            }
        }
        val needs = PermissionPlanner.needsLocationChoice(sdk, hasLocation, originalOpened)
        if (size == null) size = openLength(uri)?.takeIf { it >= 0 }
        if (size == null) size = m?.size
        val name = m?.name ?: uri.lastPathSegment ?: "file"
        if (size == null) {
            AppGraph.log("send", "can't read $uri")
            return One(PreparedItem(newId(), uri, name, 0, null, requireOriginal = false, unreadable = true), false)
        }
        val requireOriginal = Build.VERSION.SDK_INT >= 29 && originalOpened
        return One(PreparedItem(newId(), uri, name, size, m?.mtimeMs, requireOriginal), needs)
    }

    /** The service's source for one item. */
    fun sourceFor(p: PreparedItem): UploadSource =
        if (p.unreadable) UnreadableSource(p.name)
        else ContentUploadSource(cr, p.uri, p.requireOriginal, p.name) { refresh(p) }

    /** [B5] size and date again after "file changed while sending". */
    private fun refresh(p: PreparedItem): SourceMeta {
        if (p.uri.scheme == ContentResolver.SCHEME_FILE) {
            val f = File(p.uri.path!!)
            return SourceMeta(f.length(), f.lastModified().takeIf { it > 0 })
        }
        val m = queryMeta(p.uri, isMedia(p.uri))
        val opened = if (p.requireOriginal && Build.VERSION.SDK_INT >= 29) openLength(MediaStore.setRequireOriginal(p.uri)) else openLength(p.uri)
        val size = opened?.takeIf { it >= 0 } ?: m?.size ?: throw java.io.FileNotFoundException(p.uri.toString())
        return SourceMeta(size, m?.mtimeMs)
    }

    private class Meta(val name: String?, val size: Long?, val mtimeMs: Long?)

    private fun queryMeta(uri: Uri, media: Boolean): Meta? = try {
        if (media) {
            cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)?.use { c ->
                if (!c.moveToFirst()) null
                else Meta(c.str(0), c.long(1), c.long(2)?.takeIf { it > 0 }?.times(1000))
            }
        } else {
            cr.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) null
                else Meta(
                    c.str(c.getColumnIndex(OpenableColumns.DISPLAY_NAME)),
                    c.long(c.getColumnIndex(OpenableColumns.SIZE)),
                    c.long(c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED))?.takeIf { it > 0 },
                )
            }
        }
    } catch (e: Exception) {
        AppGraph.log("send", "query $uri failed: $e")
        null
    }

    /** Opens and closes the descriptor: its length (−1 = unknown), or null if it can't be opened. */
    private fun openLength(uri: Uri): Long? = try {
        cr.openFileDescriptor(uri, "r")?.use { it.statSize }
    } catch (e: Exception) {
        null
    }

    private fun assetLength(uri: Uri): Long? = try {
        cr.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it >= 0 }
    } catch (e: Exception) {
        null
    }

    private fun isMedia(uri: Uri) = uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority == MediaStore.AUTHORITY

    /** Share/SAF URI → MediaStore URI, so photos are read as originals; null when that is not possible. */
    private fun mapToMedia(uri: Uri): Uri? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        if (Build.VERSION.SDK_INT >= 29 && DocumentsContract.isDocumentUri(ctx, uri)) {
            return try { MediaStore.getMediaUri(ctx, uri) } catch (e: Throwable) { null } // [PR3]
        }
        return try {
            val path = cr.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.str(0) else null
            } ?: return null
            cr.query(MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE),
                "_data = ?", arrayOf(path), null)?.use { c ->
                if (!c.moveToFirst()) null
                else when (c.getInt(1)) {
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    else -> null
                }
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun Cursor.str(i: Int): String? = if (i < 0 || isNull(i)) null else getString(i)
    private fun Cursor.long(i: Int): Long? = if (i < 0 || isNull(i)) null else getLong(i)

    companion object {
        const val SHARE_CACHE = "share"
    }
}
