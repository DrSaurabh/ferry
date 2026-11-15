package app.ferry.core.log

import app.ferry.core.Clock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-app diagnostics log (design §9): the last [maxLines] lines in memory and, when [file] is given, the same
 * lines appended to it (`files/ferry-app-log.txt`). When the file passes [maxFileBytes] it is renamed to
 * `<name>.1` (replacing the previous one) and a new file is started, so at most about 2 × [maxFileBytes] are kept.
 * File errors are swallowed: logging must never break the app. Thread-safe.
 */
class RingLog(
    private val file: File?,
    private val clock: Clock,
    private val maxLines: Int = 2_000,
    private val maxFileBytes: Long = 1L shl 20,
) {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var out: FileOutputStream? = null
    private var fileBytes = -1L

    fun log(tag: String, msg: String) {
        val clean = msg.replace('\n', ' ').replace('\r', ' ')
        synchronized(this) {
            val line = fmt.format(Date(clock.nowMs())) + " " + tag + " " + clean
            lines.addLast(line)
            while (lines.size > maxLines) lines.removeFirst()
            if (file != null) write(file, line)
        }
    }

    fun snapshot(): List<String> = synchronized(this) { ArrayList(lines) }

    /** The rotated-out previous file, for "Share diagnostics log". */
    val previousFile: File? get() = file?.let { File(it.path + ".1") }

    private fun write(f: File, line: String) {
        try {
            if (out == null) {
                f.parentFile?.mkdirs()
                out = FileOutputStream(f, true)
                fileBytes = f.length()
            }
            val b = (line + "\n").toByteArray(Charsets.UTF_8)
            out!!.write(b)
            out!!.flush()
            fileBytes += b.size
            if (fileBytes >= maxFileBytes) rotate(f)
        } catch (e: IOException) {
            closeOut() // try again with a fresh stream next time
        }
    }

    private fun rotate(f: File) {
        closeOut()
        val old = File(f.path + ".1")
        old.delete()
        if (!f.renameTo(old)) f.delete()
    }

    private fun closeOut() {
        try {
            out?.close()
        } catch (e: IOException) {
            // ignore
        }
        out = null
    }
}
