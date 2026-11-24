package app.ferry.core.log

import app.ferry.core.Clock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Log detail level (design §7.1, L2): Normal drops [EventLog.detailed] events. */
enum class Detail { Normal, Detailed }

/**
 * The 2.1 event log of phone and TV (design §7.1, M8, M18; plan §1.2, §2.2, CP108, CP109, CP133). One line per
 * event:
 *
 *     2026-11-24 02:01:03.456 #1234 s=7f3a tap id=send screen=picker
 *
 * `#seq` grows across restarts: at start it is max(stored state, last `#seq` in the current or rotated file) + 1,
 * so a crash between state saves never reuses a number [CP133]. The state is saved at most every [saveEveryMs]
 * and by [saveState] (at background). Files: `ferry-app-log.txt` and `ferry-app-log.1.txt` in [dir], rotated at
 * [maxFileBytes] (≤ 2 × 2.5 MB). The last [tailLines] lines stay in memory for "share log" and `AppGraph.ringLog`
 * (2.0 tests search them): the tail keeps values unescaped, and a `diag` line reads `… diag <tag> <msg>` like a 2.0
 * RingLog line [PS3]. File errors are swallowed: logging never breaks the app. Thread-safe.
 */
class EventLog(
    private val dir: File,
    private val clock: Clock,
    private val state: LogStateStore,
    private val detail: () -> Detail = { Detail.Detailed },
    private val maxFileBytes: Long = MAX_FILE_BYTES,
    private val tailLines: Int = 2_000,
    zone: TimeZone = TimeZone.getDefault(),
    private val saveEveryMs: Long = 2_000,
    /** 4 hex chars, new per process start; tests pass a fixed one. */
    val session: String = newSession(),
) {
    val file: File = File(dir, FILE_NAME)
    val previousFile: File = File(dir, OLD_FILE_NAME)

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply { timeZone = zone }
    private val tail = ArrayDeque<String>()
    private var out: FileOutputStream? = null
    private var fileBytes = -1L
    private var seq: Long
    private var shippedSeq: Long
    private var lastSaveMs = 0L

    init {
        val st = state.load()
        seq = maxOf(st.seq, lastSeqIn(file) ?: lastSeqIn(previousFile) ?: 0L)
        shippedSeq = st.shippedSeq
    }

    /** Writes an event at every level. Field values: null → `""`; anything else via `toString()`. */
    fun event(name: String, vararg kv: Pair<String, Any?>) = write(name, kv)

    /** Writes an event only at [Detail.Detailed] (per-request detail, design §7.1). */
    fun detailed(name: String, vararg kv: Pair<String, Any?>) {
        if (detail() == Detail.Detailed) write(name, kv)
    }

    /** The in-memory tail (display form, see class doc). */
    fun snapshot(): List<String> = synchronized(this) { ArrayList(tail) }

    /** The last `seq` written (0 before the first line ever). */
    val lastSeq: Long get() = synchronized(this) { seq }

    /** Ship cursor (M8): the PC's `last_seq` from the last successful `POST /api/v1/logs`. */
    val shipped: Long get() = synchronized(this) { shippedSeq }

    fun markShipped(lastSeq: Long) {
        synchronized(this) {
            if (lastSeq > shippedSeq) shippedSeq = lastSeq
            save()
        }
    }

    /** At background (CP109) and in tests. */
    fun saveState() = synchronized(this) { save() }

    private fun write(name: String, kv: Array<out Pair<String, Any?>>) {
        val event = cleanName(name)
        val fields = kv.map { cleanName(it.first) to (it.second?.toString() ?: "") }
        synchronized(this) {
            val now = clock.nowMs()
            seq += 1
            val head = fmt.format(Date(now)) + " #" + seq + " s=" + session + " " + event
            val fileLine = fileLine(head, fields)
            tail.addLast(displayLine(head, event, fields))
            while (tail.size > tailLines) tail.removeFirst()
            append(fileLine)
            if (now - lastSaveMs >= saveEveryMs || now < lastSaveMs) save(now)
        }
    }

    private fun save(now: Long = clock.nowMs()) {
        lastSaveMs = now
        state.save(LogState(seq, shippedSeq, session))
    }

    private fun append(line: String) {
        try {
            if (out == null) {
                dir.mkdirs()
                out = FileOutputStream(file, true)
                fileBytes = file.length()
            }
            val b = (line + "\n").toByteArray(Charsets.UTF_8)
            out!!.write(b)
            out!!.flush()
            fileBytes += b.size
            if (fileBytes >= maxFileBytes) rotate()
        } catch (e: IOException) {
            closeOut() // try again with a fresh stream next time
        }
    }

    private fun rotate() {
        closeOut()
        previousFile.delete()
        if (!file.renameTo(previousFile)) file.delete()
    }

    private fun closeOut() {
        try {
            out?.close()
        } catch (e: IOException) {
            // ignore
        }
        out = null
    }

    companion object {
        const val FILE_NAME = "ferry-app-log.txt"
        const val OLD_FILE_NAME = "ferry-app-log.1.txt"
        const val STATE_FILE_NAME = "ferry-log-state.json"
        const val MAX_FILE_BYTES = 2_500_000L
        const val MAX_LINE_BYTES = 2_048

        /** `#1234` of a log line (plan §2.2); null when the line has none. */
        private val SEQ = Regex("""^\S+ \S+ #(\d{1,18}) """)

        fun seqOf(line: String): Long? = SEQ.find(line)?.groupValues?.get(1)?.toLongOrNull()

        private val NAME_OK = Regex("""^[a-z][a-z0-9_.]*$""")

        /** Event and field names are `[a-z][a-z0-9_.]*`; anything else is made to fit (never throws). */
        internal fun cleanName(n: String): String {
            if (NAME_OK.matches(n)) return n
            val s = n.lowercase(Locale.US).map { if (it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '.') it else '_' }
                .joinToString("")
            return if (s.isNotEmpty() && s[0] in 'a'..'z') s else "x$s"
        }

        /** Value quoting (plan §2.2): quoted when it contains a space, `"`, `=` or is empty. */
        fun quote(v: String): String {
            val needs = v.isEmpty() || v.any { it == ' ' || it == '"' || it == '=' || it == '\n' || it == '\r' || it == '\\' || it == '\t' }
            if (!needs) return v
            val sb = StringBuilder(v.length + 2).append('"')
            for (c in v) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> sb.append(c)
                }
            }
            return sb.append('"').toString()
        }

        /** The file line; the longest value is cut (with `…`) until the line is ≤ [MAX_LINE_BYTES] UTF-8 bytes. */
        internal fun fileLine(head: String, fields: List<Pair<String, String>>): String {
            val vals = fields.map { it.second }.toMutableList()
            while (true) {
                val line = head + fields.indices.joinToString("") { " " + fields[it].first + "=" + quote(vals[it]) }
                val over = line.toByteArray(Charsets.UTF_8).size - MAX_LINE_BYTES
                if (over <= 0 || vals.isEmpty()) return line
                val i = vals.indices.maxByOrNull { vals[it].length }!!
                val v = vals[i]
                if (v.length <= 1) return line.take(MAX_LINE_BYTES / 2) // pathological: hundreds of fields
                vals[i] = cutToBytes(v, v.toByteArray(Charsets.UTF_8).size - over - 3) + "…" // "…" = 3 bytes
            }
        }

        /** The longest prefix of [v] with at most [maxBytes] UTF-8 bytes (never splits a surrogate pair). */
        private fun cutToBytes(v: String, maxBytes: Int): String {
            var bytes = 0
            var i = 0
            while (i < v.length) {
                val cp = v.codePointAt(i)
                val n = when {
                    cp < 0x80 -> 1
                    cp < 0x800 -> 2
                    cp < 0x10000 -> 3
                    else -> 4
                }
                if (bytes + n > maxBytes) break
                bytes += n
                i += Character.charCount(cp)
            }
            return v.substring(0, i)
        }

        /** Tail line: values as given (searchable, PS3); `diag` as `diag <tag> <msg>` like 2.0's RingLog. */
        internal fun displayLine(head: String, event: String, fields: List<Pair<String, String>>): String {
            val flat = fields.map { it.first to it.second.replace('\n', ' ').replace('\r', ' ') }
            if (event == "diag") {
                val m = flat.toMap()
                return head + " " + (m["tag"] ?: "") + " " + (m["msg"] ?: "")
            }
            return head + flat.joinToString("") { " " + it.first + "=" + it.second }
        }

        /** The last `#seq` in [f] (reads only the end of the file); null when there is none. */
        internal fun lastSeqIn(f: File): Long? {
            if (!f.isFile) return null
            return try {
                java.io.RandomAccessFile(f, "r").use { raf ->
                    val len = raf.length()
                    val start = maxOf(0L, len - 16 * 1024)
                    val buf = ByteArray((len - start).toInt())
                    raf.seek(start)
                    raf.readFully(buf)
                    String(buf, Charsets.UTF_8).split('\n').asReversed().firstNotNullOfOrNull { seqOf(it) }
                }
            } catch (e: IOException) {
                null
            }
        }

        private val random = SecureRandom()

        fun newSession(): String = String.format(Locale.US, "%04x", random.nextInt(0x10000))

        /** `+05:30` for `app.start tz=` (plan §2.2). */
        fun tzOffset(zone: TimeZone, atMs: Long): String {
            val m = zone.getOffset(atMs) / 60_000
            val a = Math.abs(m)
            return String.format(Locale.US, "%s%02d:%02d", if (m < 0) "-" else "+", a / 60, a % 60)
        }
    }
}
