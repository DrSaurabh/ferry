package app.ferry.core.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Same vectors as `ferry.test_parse_range_unit`, plus the shared file `range-vectors.txt`, whose expected
 * values `android/ci/test_range_vectors.py` checks against `ferry.parse_range` itself (Ph3a acceptance).
 */
class RangeParserTest {
    private fun show(r: RangeParser.R) = when (r) {
        is RangeParser.R.Span -> "${r.start}-${r.end}"
        RangeParser.R.Invalid -> "invalid"
        RangeParser.R.Whole -> "whole"
    }

    @Test
    fun ferryUnitVectors() {
        val p = RangeParser
        assertEquals(RangeParser.R.Whole, p.parse(null, 10))
        assertEquals(RangeParser.R.Whole, p.parse("items=0-1", 10))
        assertEquals(RangeParser.R.Whole, p.parse("bytes=5-2", 10))
        assertEquals(RangeParser.R.Span(0, 0), p.parse("bytes=0-0", 10))
        assertEquals(RangeParser.R.Span(8, 9), p.parse("bytes=8-100", 10))
        assertEquals(RangeParser.R.Invalid, p.parse("bytes=-0", 10))
        assertEquals(RangeParser.R.Invalid, p.parse("bytes=10-", 10))
        assertEquals(RangeParser.R.Span(0, 9), p.parse("bytes=-20", 10))
        assertEquals(RangeParser.R.Whole, p.parse("bytes=abc", 10))
    }

    @Test
    fun sharedVectorsMatchFerryPy() {
        val text = javaClass.getResourceAsStream("/range-vectors.txt")!!.readBytes().toString(Charsets.UTF_8)
        var n = 0
        for (line in text.split('\n')) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val c2 = line.lastIndexOf('|')
            val c1 = line.lastIndexOf('|', c2 - 1)
            val header = line.substring(0, c1).let { if (it == "<none>") null else it }
            val size = line.substring(c1 + 1, c2).toLong()
            val expected = line.substring(c2 + 1).trimEnd('\r')
            assertEquals("header=[$header] size=$size", expected, show(RangeParser.parse(header, size)))
            n++
        }
        assertTrue("only $n vectors", n >= 30)
    }
}
