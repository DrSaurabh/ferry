package app.ferry.core.log

import java.io.File
import java.io.IOException

/** One body for `POST /api/v1/logs` (plan §2.3): whole lines ending with `\n`. */
data class LogChunk(
    val body: String,
    /** First real `#seq` in [body] (→ `X-Ferry-Log-From`); null when empty. */
    val fromSeq: Long?,
    /** Last `#seq` in [body]; null when empty. */
    val lastSeq: Long?,
) {
    val isEmpty: Boolean get() = body.isEmpty()
}

/**
 * Reads the event log for shipping (design M8; plan §1.2): the lines after a cursor `seq`, from the rotated file
 * and then the current one, newline-aligned, at most [LogChunk] `maxBytes` UTF-8 bytes. When the cursor fell off
 * rotation (the oldest line still there is newer than cursor + 1) the chunk starts with
 * `<time> #- s=---- gap from=<cursor+1> to=<oldest-1>` (CP136: `gap` is the one event written outside `EventLog`).
 */
class LogReader(private val dir: File) {
    fun linesAfter(cursor: Long, maxBytes: Int = MAX_BYTES): LogChunk {
        val sb = StringBuilder()
        var bytes = 0
        var from: Long? = null
        var last: Long? = null
        var oldest: Long? = null
        var oldestTime = ""
        var full = false
        for (name in listOf(EventLog.OLD_FILE_NAME, EventLog.FILE_NAME)) {
            if (full) break
            val f = File(dir, name)
            if (!f.isFile) continue
            try {
                f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        val seq = EventLog.seqOf(line) ?: continue
                        if (oldest == null) {
                            oldest = seq
                            oldestTime = line.substring(0, minOf(23, line.length))
                        }
                        if (seq <= cursor) continue
                        if (from == null && seq > cursor + 1 && oldest == seq) {
                            val gap = "$oldestTime #- s=---- gap from=${cursor + 1} to=${seq - 1}\n"
                            sb.append(gap)
                            bytes += gap.toByteArray(Charsets.UTF_8).size
                        }
                        val n = line.toByteArray(Charsets.UTF_8).size + 1
                        if (bytes + n > maxBytes && from != null) {
                            full = true
                            break
                        }
                        sb.append(line).append('\n')
                        bytes += n
                        if (from == null) from = seq
                        last = seq
                    }
                }
            } catch (e: IOException) {
                // a file rotated away while reading: ship what was read; the next round goes on
            }
        }
        return LogChunk(sb.toString(), from, last)
    }

    companion object {
        const val MAX_BYTES = 512 * 1024
    }
}
