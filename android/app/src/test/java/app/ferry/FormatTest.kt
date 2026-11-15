package app.ferry

import app.ferry.ui.common.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatTest {
    @Test fun sizesMatchThePcConsole() {
        assertEquals("0 B", Format.size(0))
        assertEquals("999 B", Format.size(999))
        assertEquals("1.0 KB", Format.size(1000))
        assertEquals("1.5 KB", Format.size(1500))
        assertEquals("12.3 MB", Format.size(12_340_000))
        assertEquals("1.4 GB", Format.size(1_400_000_000))
        assertEquals("2500.0 GB", Format.size(2_500_000_000_000))
    }

    @Test fun speed() {
        assertEquals("18.2 MB/s", Format.speed(18_200_000.0))
        assertEquals("", Format.speed(0.0))
        assertEquals("", Format.speed(Double.NaN))
    }

    @Test fun eta() {
        assertNull(Format.eta(null))
        assertEquals("less than a minute left", Format.eta(59))
        assertEquals("about 1 min left", Format.eta(60))
        assertEquals("about 1 min left", Format.eta(89))
        assertEquals("about 2 min left", Format.eta(90))
        assertEquals("about 1 h left", Format.eta(3600))
        assertEquals("about 2 h 5 min left", Format.eta(2 * 3600 + 5 * 60))
    }

    @Test fun ages() {
        val now = 10_000_000_000L
        assertEquals("", Format.age(null, now))
        assertEquals("just now", Format.age(now - 30_000, now))
        assertEquals("5 min ago", Format.age(now - 5 * 60_000, now))
        assertEquals("3 h ago", Format.age(now - 3 * 3_600_000, now))
        assertEquals("yesterday", Format.age(now - 30 * 3_600_000L, now))
        assertEquals("4 days ago", Format.age(now - 4 * 86_400_000L, now))
    }
}
