package app.ferry.core

import app.ferry.core.util.Hex
import app.ferry.core.util.PercentEncode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PercentEncodeTest {
    @Test
    fun deviceNameWithCurlyQuoteAndEmoji() { // [B10]
        val enc = PercentEncode.utf8("Saurabh’s phone 📱")
        // Python: urllib.parse.quote('Saurabh’s phone 📱', safe='-._~'); unquote gives the name back.
        assertEquals("Saurabh%E2%80%99s%20phone%20%F0%9F%93%B1", enc)
        assertTrue(enc.all { it.code in 0x21..0x7e })
    }

    @Test
    fun reservedCharactersAreEncoded() {
        assertEquals("a%2Bb%20c%25d%26e%3Df%2Fg.jpg", PercentEncode.utf8("a+b c%d&e=f/g.jpg"))
        assertEquals("AZaz09-._~", PercentEncode.utf8("AZaz09-._~"))
    }

    @Test
    fun hex() {
        assertEquals("00ff10ab", Hex.encode(byteArrayOf(0, -1, 16, 0xab.toByte())))
        assertEquals("", Hex.encode(ByteArray(0)))
    }
}
