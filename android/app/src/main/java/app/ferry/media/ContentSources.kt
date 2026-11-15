package app.ferry.media

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import app.ferry.core.net.FerryException
import app.ferry.core.upload.OpenedSource
import app.ferry.core.upload.SourceMeta
import app.ferry.core.upload.UploadSource
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
