package app.ferry.core.download

import app.ferry.core.net.FerryException
import java.io.IOException
import java.io.OutputStream

/**
 * Where a download goes; implemented by `:app` (MediaStore on API 29+, app-private file) [CP17].
 * Contract [PB17]: bytes are counted after each write(); when the body ends the caller closes the stream
 * FIRST (sinks flush and fd.sync() in close()), then compares the byte count with the expected size, and
 * only then calls commit(); any failure or mismatch → close() then abort(). commit() never sees an open stream.
 * [SinkWriter] is the one implementation of that order.
 */
interface DownloadSink {
    fun open(): OutputStream

    /** Returns the final file name. */
    fun commit(): String

    fun abort()

    /** What "Open" hands to the system after commit (a content:// URI on Android, a path in tests) (Ph3a, B32). */
    fun openUri(): String = ""
}

/**
 * Writes one response body into a [DownloadSink] in the order of the contract above [PB17] [B7]:
 * [start] opens the sink, [write] counts bytes, [finish] closes, compares with [expected], then commits;
 * [abort] (after any failure) closes, then aborts. Used by `FerryClient.download` and by the test fakes, so
 * the order is the same code everywhere.
 *
 * Ph7 (B58): an `IOException` from the **sink** (open, write, close/sync, commit) is the phone's storage, not the
 * network: it is thrown as [FerryException.NotEnoughSpace] when it says the disk is full (ENOSPC), otherwise as
 * [FerryException.SaveFailed], so the queue fails the file at once instead of retrying it as a dropped connection.
 */
class SinkWriter(private val sink: DownloadSink, private val expected: Long, private val onProgress: (Long) -> Unit) {
    private var out: OutputStream? = null
    private var finished = false

    var got = 0L
        private set

    fun start() {
        check(out == null) { "already started" }
        out = local { sink.open() }
    }

    fun write(buf: ByteArray, n: Int) {
        val o = checkNotNull(out) { "not started" }
        local { o.write(buf, 0, n) }
        got += n
        onProgress(got)
    }

    /** Close FIRST, then compare, then commit; returns the committed name. */
    fun finish(): String {
        val o = checkNotNull(out) { "not started" }
        out = null
        local { o.close() }
        if (got != expected) throw FerryException.BadReply("got $got bytes, expected $expected")
        val name = local { sink.commit() }
        finished = true
        return name
    }

    /** Runs a sink call; its IOExceptions become phone-storage errors (B58). FerryExceptions pass unchanged. */
    private inline fun <T> local(block: () -> T): T = try {
        block()
    } catch (e: FerryException) {
        throw e
    } catch (e: IOException) {
        if (isNoSpace(e)) throw FerryException.NotEnoughSpace(expected - got, 0) else throw FerryException.SaveFailed(e)
    }

    /** After a failure: close (if still open), then abort. Never throws; does nothing after a commit. */
    fun abort() {
        if (finished) return
        finished = true
        out?.let {
            out = null
            try {
                it.close()
            } catch (e: IOException) {
                // the abort below discards the data anyway
            }
        }
        try {
            sink.abort()
        } catch (e: Exception) {
            // nothing more we can do
        }
    }

    companion object {
        /** ENOSPC anywhere in the cause chain ("No space left on device"). */
        fun isNoSpace(e: Throwable): Boolean {
            var t: Throwable? = e
            while (t != null) {
                val m = t.message.orEmpty()
                if (m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)) return true
                t = t.cause
            }
            return false
        }
    }
}
