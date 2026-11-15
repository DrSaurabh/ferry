package app.ferry.core.download

/**
 * Where a download goes; implemented by `:app` (MediaStore on API 29+, app-private file) [CP17].
 * Contract [PB17]: bytes are counted after each write(); when the body ends the caller closes the stream
 * FIRST (sinks flush and fd.sync() in close()), then compares the byte count with the expected size, and
 * only then calls commit(); any failure or mismatch → close() then abort(). commit() never sees an open stream.
 */
interface DownloadSink {
    fun open(): java.io.OutputStream
    /** Returns the final file name. */
    fun commit(): String
    fun abort()
}
