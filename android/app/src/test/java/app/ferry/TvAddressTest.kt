package app.ferry

import app.ferry.cast.TvAddress
import app.ferry.core.HostPort
import app.ferry.ui.tv.SlideshowIntervals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Stage 7 audit: the typed TV address (O2) and the TV slideshow's Up / Down steps (O8). */
class TvAddressTest {
    @Test
    fun plainAddressMeansTheReceiverPort() {
        assertEquals(HostPort("192.168.1.30", 8766), TvAddress.parse("192.168.1.30"))
        assertEquals(HostPort("192.168.1.30", 8766), TvAddress.parse("  192.168.1.30 "))
        assertEquals(HostPort("192.168.1.30", 8766), TvAddress.parse("http://192.168.1.30/"))
        assertEquals(HostPort("10.0.0.7", 8766), TvAddress.parse("010.0.0.07"))
    }

    @Test
    fun aTypedPortIsKept() {
        assertEquals(HostPort("192.168.1.30", 8770), TvAddress.parse("192.168.1.30:8770"))
        assertEquals(HostPort("192.168.1.30", 8765), TvAddress.parse("192.168.1.30:8765"))
        assertEquals(HostPort("192.168.1.30", 8770), TvAddress.parse("http://192.168.1.30:8770"))
    }

    @Test
    fun notAnAddress() {
        for (bad in listOf("", "192.168.1.", "192.168.1", "tv", "192.168.1.300", "192.168.1.30:", "192.168.1.30:99999")) {
            assertNull(bad, TvAddress.parse(bad))
        }
    }

    @Test
    fun slideshowSteps() {
        assertEquals(listOf(3, 5, 10), SlideshowIntervals.CHOICES)
        assertEquals(5, SlideshowIntervals.step(3, up = true))
        assertEquals(10, SlideshowIntervals.step(5, up = true))
        assertEquals(10, SlideshowIntervals.step(10, up = true))
        assertEquals(5, SlideshowIntervals.step(10, up = false))
        assertEquals(3, SlideshowIntervals.step(5, up = false))
        assertEquals(3, SlideshowIntervals.step(3, up = false))
        // a value from an older setting or a cast (e.g. 4 s, 60 s) lands on the nearest choice in that direction
        assertEquals(5, SlideshowIntervals.step(4, up = true))
        assertEquals(3, SlideshowIntervals.step(4, up = false))
        assertEquals(10, SlideshowIntervals.step(60, up = false))
        assertEquals(10, SlideshowIntervals.step(60, up = true))
    }
}
