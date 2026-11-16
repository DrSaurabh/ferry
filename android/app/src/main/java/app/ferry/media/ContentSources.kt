package app.ferry.media

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import app.ferry.AppGraph
import app.ferry.core.cast.CastFile
import app.ferry.core.http.RangeSource
import app.ferry.core.net.FerryException
import app.ferry.core.upload.OpenedSource
import app.ferry.core.upload.SourceMeta
import app.ferry.core.upload.UploadSource
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * `UploadSource` over a content (or file) URI (plan §4.7). With [requireOriginal] the URI is opened through
 * `MediaStore.setRequireOriginal` only; a failure is `OriginalUnreadable` and there is **no fallback** to the plain,
 * redacted stream [PB5] [H19]. [refreshMeta] re-reads size and date after "file changed" [B5].
 */
class ContentUploadSource(
    private val cr: ContentResolver,
    private val uri: Uri,
    private val requireOriginal: Boolean,
    override val displayName: String,
    private val refreshMeta: () -> SourceMeta,
) : UploadSource {
    override fun open(): OpenedSource {
        val target = if (requireOriginal && Build.VERSION.SDK_INT >= 29) MediaStore.setRequireOriginal(uri) else uri
        val pfd = try {
            cr.openFileDescriptor(target, "r")
        } catch (e: Exception) {
            throw if (requireOriginal) FerryException.OriginalUnreadable(e) else FerryException.SourceUnreadable(e)
        } ?: throw if (requireOriginal) FerryException.OriginalUnreadable(null) else FerryException.SourceUnreadable(null)
        val len = pfd.statSize
        val input = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        return object : OpenedSource {
            override val length: Long = if (len >= 0) len else -1
            override val stream: InputStream = input
            override fun close() = input.close()
        }
    }

    override fun refresh(): SourceMeta = refreshMeta()
}

/** A share URI that could not be read while preparing: the engine marks it "Can't read this file any more". */
class UnreadableSource(override val displayName: String) : UploadSource {
    override fun open(): OpenedSource = throw FerryException.SourceUnreadable(null)
    override fun refresh(): SourceMeta = throw FerryException.SourceUnreadable(null)
}

/**
 * The phone's media for a cast (design §6): a [RangeSource] over a MediaStore URI. Each request opens the file and
 * **positions the channel** at the range start, so a seek on the TV costs nothing; the stream closes the descriptor.
 * [etag] = size-date, so the TV's `If-Range` notices a file that changed.
 */
class ContentRangeSource(
    private val cr: ContentResolver,
    private val uri: Uri,
    override val length: Long,
    override val etag: String,
) : RangeSource {
    override fun openAt(offset: Long): InputStream {
        val pfd = cr.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
        val input = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        if (offset > 0) {
            try {
                input.channel.position(offset)
            } catch (e: IOException) { // not a seekable file (unusual provider): skip instead
                var left = offset
                while (left > 0) {
                    val n = input.skip(left)
                    if (n <= 0) {
                        input.close()
                        throw IOException("can't reach byte $offset of $uri")
                    }
                    left -= n
                }
            }
        }
        return input
    }
}

/** Picker items → the files a cast serves (name, MIME type and size from MediaStore; the size from the file itself). */
object CastFiles {
    fun build(cr: ContentResolver, picks: List<MediaPick>): List<CastFile> = picks.mapNotNull { p ->
        val base = if (p.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = ContentUris.withAppendedId(base, p.id)
        try {
            var name = "item-${p.id}"
            var mime = if (p.isVideo) "video/mp4" else "image/jpeg"
            var size = -1L
            var date = 0L
            cr.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    c.getString(1)?.takeIf { it.contains('/') }?.let { mime = it }
                    if (!c.isNull(2)) size = c.getLong(2)
                    if (!c.isNull(3)) date = c.getLong(3)
                }
            }
            cr.openFileDescriptor(uri, "r")?.use { if (it.statSize >= 0) size = it.statSize }
            if (size < 0) null else CastFile(name, mime, ContentRangeSource(cr, uri, size, "$size-$date"))
        } catch (e: Exception) { // gone or unreadable: left out of the cast
            AppGraph.log("cast", "can't offer ${p.id}: $e")
            null
        }
    }
}
