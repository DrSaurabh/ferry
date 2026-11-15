package app.ferry.core.http

import java.math.BigInteger

/**
 * Single-range `Range` header parsing with exactly the semantics of `ferry.parse_range` (design §7):
 * anything not understood (other unit, several ranges, junk, `b < a`) → [R.Whole]; a range that starts past the
 * end, `-0`, or any suffix of an empty file → [R.Invalid] (416); otherwise an inclusive [R.Span] clamped to the
 * file. The shared vectors in `src/test/resources/range-vectors.txt` are checked against both implementations.
 */
object RangeParser {
    sealed interface R {
        data class Span(val start: Long, val end: Long) : R
        object Invalid : R
        object Whole : R
    }

    fun parse(header: String?, size: Long): R {
        if (header.isNullOrEmpty()) return R.Whole
        val h = header.trim()
        if (!h.lowercase().startsWith("bytes=")) return R.Whole
        val spec = h.substring(6).trim()
        if (spec.contains(',')) return R.Whole
        val dash = spec.indexOf('-')
        if (dash < 0) return R.Whole
        val a = spec.substring(0, dash).trim()
        val b = spec.substring(dash + 1).trim()
        if (a.isEmpty()) {
            if (!isDigits(b)) return R.Whole
            val n = BigInteger(b)
            if (n.signum() == 0 || size == 0L) return R.Invalid
            return R.Span(maxOf(BigInteger.ZERO, BigInteger.valueOf(size) - n).toLong(), size - 1)
        }
        if (!isDigits(a) || (b.isNotEmpty() && !isDigits(b))) return R.Whole
        val big = BigInteger.valueOf(size)
        val start = BigInteger(a)
        val end = if (b.isNotEmpty()) BigInteger(b) else big - BigInteger.ONE
        if (b.isNotEmpty() && end < start) return R.Whole
        if (start >= big) return R.Invalid
        return R.Span(start.toLong(), minOf(end, big - BigInteger.ONE).toLong())
    }

    /** Python's `str.isdigit()` for the ASCII digits a header can carry (empty → false); numbers are big ints there too. */
    private fun isDigits(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' }
}
