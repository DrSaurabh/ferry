package app.ferry.core.download

import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * A [DownloadSink] into a plain file in a folder only Ferry writes to (the app update `files/update/Ferry.apk`,
 * the TV's APK downloads, tests): writes `<target>.part`, syncs it on close, and [commit] renames it onto
 * [target] (an older file of that name is replaced: these folders are Ferry's own, never the user's) [CP17].
 * [abort] deletes the part file.
 */
open class FileSink(val target: File) : DownloadSink {
    val part = File(target.path + ".part")

    override fun open(): OutputStream {
        target.parentFile?.mkdirs()
        val fos = FileOutputStream(part)
        return object : FilterOutputStream(fos) {
            private var closed = false
            override fun write(b: ByteArray, off: Int, len: Int) = fos.write(b, off, len)
            override fun close() {
                if (closed) return
                closed = true
                try {
                    fos.flush()
                    fos.fd.sync()
                } finally {
                    fos.close()
                }
            }
        }
    }

    override fun commit(): String {
        if (target.exists() && !target.delete()) throw IOException("cannot replace $target")
        if (!part.renameTo(target)) throw IOException("cannot rename $part")
        return target.name
    }

    override fun abort() {
        part.delete()
    }

    override fun openUri(): String = target.path
}
