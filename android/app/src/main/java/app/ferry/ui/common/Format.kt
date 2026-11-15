package app.ferry.ui.common

import java.util.Locale

/**
 * Number formatting for the screens (plan §4.12): sizes with 1 decimal and 1000-based units B/KB/MB/GB,
 * exactly like the PC console's `fmt_size`; speeds in MB/s; ETA in plain words; ages of outbox files.
 * Pure Kotlin (unit-tested in FormatTest).
 */
object Format {
    fun size(bytes: Long): String {
        var n = bytes.toDouble()
        for (unit in arrayOf("B", "KB", "MB", "GB")) {
            if (n < 1000 || unit == "GB") {
                return if (unit == "B") "${n.toLong()} B" else String.format(Locale.US, "%.1f %s", n, unit)
            }
            n /= 1000.0
        }
        return String.format(Locale.US, "%.1f GB", n)
    }

    /** "18.2 MB/s"; empty when there is nothing meaningful to show yet. */
    fun speed(bytesPerSecond: Double): String =
        if (bytesPerSecond <= 0.0 || bytesPerSecond.isNaN()) "" else String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1e6)

    /** "less than a minute left", "about 1 min left", "about 12 min left", "about 2 h 5 min left"; null if unknown. */
    fun eta(seconds: Long?): String? {
        if (seconds == null || seconds < 0) return null
        if (seconds < 60) return "less than a minute left"
        val min = (seconds + 30) / 60
        if (min < 60) return "about $min min left"
        val h = min / 60
        val m = min % 60
        return if (m == 0L) "about $h h left" else "about $h h $m min left"
    }

    /** Age of a file for lists: "just now", "5 min ago", "3 h ago", "yesterday", "4 days ago"; "" if unknown. */
    fun age(mtimeMs: Long?, nowMs: Long): String {
        if (mtimeMs == null || mtimeMs <= 0) return ""
        val s = (nowMs - mtimeMs) / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            s < 2 * 86_400 -> "yesterday"
            else -> "${s / 86_400} days ago"
        }
    }
}
