package app.ferry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostPortTest {
    @Test
    fun parseForms() {
        assertEquals(HostPort("192.168.1.5", 8765), HostPort.parse("192.168.1.5"))
        assertEquals(HostPort("192.168.1.5", 8800), HostPort.parse("192.168.1.5:8800"))
        assertEquals(HostPort("192.168.1.5", 8765), HostPort.parse("  192.168.1.5 \n"))
        assertEquals(HostPort("10.0.2.2", 18765), HostPort.parse("http://10.0.2.2:18765/"))
        assertEquals(HostPort("192.168.1.5", 8765), HostPort.parse("192.168.001.005"))
    }

    @Test
    fun junkIsNull() {
        for (s in listOf(
            "", " ", "abc", "192.168.1", "192.168.1.5.6", "256.1.1.1", "192.168.1.5:", "192.168.1.5:0",
            "192.168.1.5:65536", "192.168.1.5:80a", "192.168.1.-5", "fe80::1", "[::1]:8765", "pc.local",
            "192.168.1.5:8765:1", "1234.1.1.1",
        )) {
            assertNull("'$s'", HostPort.parse(s))
        }
    }

    @Test
    fun toStringOmitsDefaultPort() {
        assertEquals("192.168.1.5", HostPort("192.168.1.5").toString())
        assertEquals("192.168.1.5:8800", HostPort("192.168.1.5", 8800).toString())
        assertEquals(HostPort("192.168.1.5", 8800), HostPort.parse(HostPort("192.168.1.5", 8800).toString()))
    }
}
