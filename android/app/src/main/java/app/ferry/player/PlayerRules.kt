package app.ferry.player

import java.util.Locale

/**
 * Resume rule of the TV player (design §5.2 [A12], plan §4.10): the position is saved every 5 s and on stop, keyed by
 * `name|size|mtime`; reopening offers "Resume from 12:34" only if the saved position is more than 30 s in and more
 * than 30 s before the end. Pure Kotlin (`ResumeRuleTest`).
 */
object ResumeRule {
    const val EDGE_MS = 30_000L
    const val MAX_ENTRIES = 200

    /** A saved position; [durMs] ≤ 0 = the duration was not known yet. */
    data class Entry(val posMs: Long, val durMs: Long, val savedAtMs: Long)

    fun key(name: String, size: Long, mtime: Long?): String = "$name|$size|${mtime ?: 0}"

    /** Offer "Resume from …"? (An unknown duration only needs the 30 s at the start.) */
    fun offer(posMs: Long, durMs: Long): Boolean =
        posMs > EDGE_MS && (durMs <= 0 || posMs < durMs - EDGE_MS)

    fun encode(e: Entry): String = "${e.posMs};${e.durMs};${e.savedAtMs}"

    fun decode(s: String?): Entry? {
        val p = s?.split(';') ?: return null
        if (p.size != 3) return null
        val pos = p[0].toLongOrNull() ?: return null
        val dur = p[1].toLongOrNull() ?: return null
        val at = p[2].toLongOrNull() ?: return null
        return if (pos < 0) null else Entry(pos, dur, at)
    }

    /**
     * The keys to drop so that at most [max] entries remain after [adding] is stored: the oldest saves first.
     * [saved] maps key → time saved.
     */
    fun evict(saved: Map<String, Long>, adding: String, max: Int = MAX_ENTRIES): List<String> {
        val others = saved.filterKeys { it != adding }
        val over = others.size + 1 - max
        if (over <= 0) return emptyList()
        return others.entries.sortedWith(compareBy({ it.value }, { it.key })).take(over).map { it.key }
    }

    /** "0:40", "12:34", "1:02:03". */
    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
    }
}

/**
 * A short codec name for "This TV can't play this video format (HEVC)" / "…sound (DTS)" [A12]: from the track's
 * `codecs` string if it is a plain name, else from its MIME type ("audio/vnd.dts" → "DTS", "video/hevc" → "HEVC").
 */
object CodecName {
    fun of(sampleMimeType: String?, codecs: String?): String {
        val mime = sampleMimeType?.substringAfter('/')?.removePrefix("x-")?.removePrefix("vnd.")
        if (!mime.isNullOrBlank()) return mime.uppercase(Locale.US)
        val c = codecs?.substringBefore(',')?.substringBefore('.')
        return if (c.isNullOrBlank()) "unknown" else c.uppercase(Locale.US)
    }
}
