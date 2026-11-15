package app.ferry.core.discovery

import app.ferry.core.FerryJson
import app.ferry.core.model.DiscoveryReply
import app.ferry.core.model.Peer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** Discovery datagrams (design §2.4, plan §3.7). */
class DiscoveryCodecTest {
    private val from = InetAddress.getByName("192.168.1.5")

    @Test
    fun requestHasTheWireFormatOfDesign24() {
        val b = DiscoveryCodec.request("vivo X200 Pro", "phone", "0a1b2c3d")
        val o = FerryJson.parseToJsonElement(String(b, Charsets.UTF_8)).jsonObject
        assertEquals("ferry", o["app"]!!.jsonPrimitive.content)
        assertEquals("discover", o["q"]!!.jsonPrimitive.content)
        assertEquals(2, o["protocol"]!!.jsonPrimitive.int)
        assertEquals("vivo X200 Pro", o["name"]!!.jsonPrimitive.content)
        assertEquals("phone", o["device_type"]!!.jsonPrimitive.content)
        assertEquals("0a1b2c3d", o["nonce"]!!.jsonPrimitive.content)
    }

    @Test
    fun requestRoundTrip() {
        val b = DiscoveryCodec.request("Saurabh’s phone 📱", "phone", "deadbeef")
        val r = DiscoveryCodec.parseRequest(b, b.size)
        assertNotNull(r)
        assertEquals("Saurabh’s phone 📱", r!!.name)
        assertEquals("phone", r.deviceType)
        assertEquals("deadbeef", r.nonce)
    }

    @Test
    fun replyRoundTrip() {
        val b = DiscoveryCodec.reply(DiscoveryReply(nonce = "0123abcd", name = "Living room TV", deviceType = "tv", port = 8766, version = "2.0.5"))
        val p = DiscoveryCodec.parseReply(b, b.size, "0123abcd", from)
        assertEquals(Peer("Living room TV", "tv", "192.168.1.5", 8766, "2.0.5", 2), p)
    }

    @Test
    fun parsesAReplyExactlyAsFerryPyWritesIt() { // json.dumps(...) with spaces, from ferry.py DiscoveryResponder.handle
        val text = """{"app": "ferry", "protocol": 2, "nonce": "0123abcd", "name": "SAURABH-PC", "device_type": "pc", "port": 8800, "version": "2.0.0"}"""
        val b = text.toByteArray()
        assertEquals(Peer("SAURABH-PC", "pc", "192.168.1.5", 8800, "2.0.0", 2), DiscoveryCodec.parseReply(b, b.size, "0123abcd", from))
    }

    @Test
    fun nonceMismatchIsNull() {
        val b = DiscoveryCodec.reply(DiscoveryReply(nonce = "0123abcd", name = "PC", deviceType = "pc", port = 8765, version = "2.0.0"))
        assertNull(DiscoveryCodec.parseReply(b, b.size, "0123abce", from))
    }

    @Test
    fun overMaxBytesIsNull() {
        val pad = "x".repeat(600)
        val text = """{"app":"ferry","protocol":2,"nonce":"0123abcd","name":"$pad","device_type":"pc","port":8765,"version":"2"}"""
        val b = text.toByteArray()
        assertTrue(b.size > DiscoveryCodec.MAX_BYTES)
        assertNull(DiscoveryCodec.parseReply(b, b.size, "0123abcd", from))
        val req = """{"app":"ferry","q":"discover","name":"$pad","device_type":"phone","nonce":"0123abcd"}""".toByteArray()
        assertNull(DiscoveryCodec.parseRequest(req, req.size))
    }

    @Test
    fun nonJsonAndWrongShapesAreNull() {
        for (t in listOf("hello", "", "[1,2]", "{\"app\":\"ferry\"", "\u0000\u0001", "{\"app\":\"other\",\"q\":\"discover\",\"nonce\":\"0123abcd\"}",
            "{\"app\":\"ferry\",\"q\":\"discover\",\"nonce\":\"xyz\"}", "{\"app\":\"ferry\",\"q\":\"what\",\"nonce\":\"0123abcd\"}")) {
            val b = t.toByteArray()
            assertNull(t, DiscoveryCodec.parseRequest(b, b.size))
            assertNull(t, DiscoveryCodec.parseReply(b, b.size, "0123abcd", from))
        }
        val badPort = """{"app":"ferry","nonce":"0123abcd","device_type":"pc","port":70000}""".toByteArray()
        assertNull(DiscoveryCodec.parseReply(badPort, badPort.size, "0123abcd", from))
    }

    @Test
    fun onlyTheGivenLengthIsRead() {
        val b = DiscoveryCodec.request("a", "phone", "0123abcd")
        val buf = ByteArray(2048)
        System.arraycopy(b, 0, buf, 0, b.size)
        buf[b.size] = '}'.code.toByte() // junk after the datagram in a reused buffer
        assertNotNull(DiscoveryCodec.parseRequest(buf, b.size))
    }

    @Test
    fun longNamesAreCutSoTheRequestFits() {
        val b = DiscoveryCodec.request("📱".repeat(300), "phone", DiscoveryCodec.newNonce())
        assertTrue("${b.size} bytes", b.size <= DiscoveryCodec.MAX_BYTES)
        assertNotNull(DiscoveryCodec.parseRequest(b, b.size))
    }

    @Test
    fun nonceIsEightHex() {
        val n = DiscoveryCodec.newNonce()
        assertTrue(n, Regex("^[0-9a-f]{8}$").matches(n))
    }
}
