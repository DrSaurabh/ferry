package app.ferry.core

import app.ferry.core.upload.OpenedSource
import app.ferry.core.upload.SourceMeta
import app.ferry.core.upload.UploadItem
import app.ferry.core.upload.UploadSource
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** In-memory upload source for tests. */
class BytesSource(private val bytes: ByteArray, override val displayName: String = "test") : UploadSource {
    override fun open(): OpenedSource = Opened(ByteArrayInputStream(bytes), bytes.size.toLong())
    override fun refresh(): SourceMeta = SourceMeta(bytes.size.toLong(), null)
}

class Opened(override val stream: InputStream, override val length: Long) : OpenedSource {
    override fun close() = stream.close()
}

private val ids = AtomicLong()

fun itemOf(bytes: ByteArray, name: String, mtimeMs: Long? = null): UploadItem =
    UploadItem(ids.incrementAndGet(), BytesSource(bytes, name), name, bytes.size.toLong(), mtimeMs)

fun sha256(bytes: ByteArray): String =
    app.ferry.core.util.Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes))
