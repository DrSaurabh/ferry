package app.ferry.core.net

import app.ferry.core.upload.OpenedSource
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.InputStream

/**
 * One-shot upload body streaming [opened] in 256 KB chunks (design §4.4).
 *
 * Data safety [PB1]: ferry.py commits a file as soon as it has read exactly Content-Length bytes, so a
 * source that has MORE than [size] bytes must be detected before the last byte of [size] reaches the
 * socket. The body therefore always holds back the most recently read chunk; when [size] bytes have been
 * read it reads once more, and if that yields data (the file grew) it throws [FerryException.SourceChanged]
 * with the held chunk unwritten: the PC sees a short body and deletes its temp file. A source that ends
 * early also throws SourceChanged [B5].
 */
class StreamingBody(
    private val opened: OpenedSource,
    private val size: Long,
    private val onProgress: (Long) -> Unit,
) : RequestBody() {
    /** True after the last byte was written and flushed [B18]. */
    @Volatile var bodyFullySent = false
        private set

    /** Bytes handed to the socket so far. */
    @Volatile var bytesSent = 0L
        private set

    @Volatile private var used = false

    override fun contentType(): MediaType = OCTET
    override fun contentLength(): Long = size
    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        check(!used) { "StreamingBody is one-shot" }
        used = true
        val input = opened.stream
        var held = ByteArray(CHUNK)
        var spare = ByteArray(CHUNK)
        var heldLen = 0
        var read = 0L
        while (read < size) {
            val want = minOf(CHUNK.toLong(), size - read).toInt()
            val n = readSource { readFully(input, spare, want) }
            if (n < want) throw FerryException.SourceChanged() // the file got shorter
            read += n
            if (heldLen > 0) send(sink, held, heldLen)
            val t = held; held = spare; spare = t
            heldLen = n
        }
        // Look ahead before the last bytes go out: any further byte means the file grew [PB1].
        if (readSource { input.read() } >= 0) throw FerryException.SourceChanged()
        if (heldLen > 0) send(sink, held, heldLen) else onProgress(0) // heldLen is 0 only when size == 0
        sink.flush()
        bodyFullySent = true
    }

    /** A read error of the phone's file is not a network error (Ph2b): it must not be retried as one. */
    private inline fun <T> readSource(block: () -> T): T = try {
        block()
    } catch (e: FerryException) {
        throw e
    } catch (e: java.io.IOException) {
        throw FerryException.SourceUnreadable(e)
    }

    private fun send(sink: BufferedSink, buf: ByteArray, len: Int) {
        sink.write(buf, 0, len)
        bytesSent += len
        onProgress(bytesSent)
    }

    companion object {
        const val CHUNK = 256 * 1024
        private val OCTET = "application/octet-stream".toMediaType()

        /** Reads until [want] bytes or end of stream; returns the count read. */
        internal fun readFully(input: InputStream, buf: ByteArray, want: Int): Int {
            var got = 0
            while (got < want) {
                val n = input.read(buf, got, want - got)
                if (n < 0) break
                got += n
            }
            return got
        }
    }
}
