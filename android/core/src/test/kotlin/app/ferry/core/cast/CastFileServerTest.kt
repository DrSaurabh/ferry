package app.ferry.core.cast

import app.ferry.core.http.BytesRangeSource
import app.ferry.core.net.FerryHttp
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.random.Random

/** Phone-side cast file server (design §6, plan §3.7). */
class CastFileServerTest {
    private val video = Random(1).nextBytes(300_000)
    private val photo = Random(2).nextBytes(20_000)
    private val token = "0123456789abcdef0123456789abcdef"
    private val items = listOf(
        CastFile("VID_20250101_120000.mp4", "video/mp4", BytesRangeSource(video, "v1")),
        CastFile("Holiday café 🎉 #1.jpg", "image/jpeg", BytesRangeSource(photo, "p1")),
    )
    private val servers = mutableListOf<CastFileServer>()
    private val http = FerryHttp.newClient(null, "Living room TV")

    @After
    fun tearDown() = servers.forEach { it.close() }

    private fun start(port: Int = 0, t: String = token): Pair<CastFileServer, Int> {
        val s = CastFileServer(items, port, t, log = { println("[cast] $it") })
        servers += s
        return s to s.start()
    }

    private fun <T> get(url: String, vararg headers: Pair<String, String>, method: String = "GET", read: (Response) -> T): T {
        val b = Request.Builder().url(url)
        headers.forEach { b.header(it.first, it.second) }
        if (method == "HEAD") b.head() else b.get()
        return http.newCall(b.build()).execute().use(read)
    }

    @Test
    fun rightTokenServesBytesWithMimeAndRanges() {
        val (s, port) = start()
        val url = s.urlFor(0, "127.0.0.1")
        assertEquals("http://127.0.0.1:$port/c/$token/0/VID_20250101_120000.mp4", url)
        get(url) { r ->
            assertEquals(200, r.code)
            assertEquals("video/mp4", r.header("Content-Type"))
            assertEquals("bytes", r.header("Accept-Ranges"))
            assertArrayEquals(video, r.body!!.bytes())
        }
        get(url, "Range" to "bytes=1000-1999") { r ->
            assertEquals(206, r.code)
            assertEquals("bytes 1000-1999/300000", r.header("Content-Range"))
            assertArrayEquals(video.copyOfRange(1000, 2000), r.body!!.bytes())
        }
        get(url, "Range" to "bytes=-100") { r ->
            assertEquals(206, r.code)
            assertArrayEquals(video.copyOfRange(299_900, 300_000), r.body!!.bytes())
        }
        get(url, "Range" to "bytes=400000-") { r -> assertEquals(416, r.code) }
        get(url, method = "HEAD") { r ->
            assertEquals(200, r.code)
            assertEquals("300000", r.header("Content-Length"))
        }
        get(s.urlFor(1, "127.0.0.1")) { r ->
            assertEquals("image/jpeg", r.header("Content-Type"))
            assertArrayEquals(photo, r.body!!.bytes())
        }
    }

    @Test
    fun wrongTokenOrIndexIs404() {
        val (s, port) = start()
        val base = "http://127.0.0.1:$port/c"
        for (path in listOf(
            "$base/ffffffffffffffffffffffffffffffff/0/VID_20250101_120000.mp4", // wrong token
            "$base/${token.dropLast(1)}/0/x.mp4",                                // prefix of the token
            "$base/$token/2/x.mp4",                                               // index out of range
            "$base/$token/-1/x.mp4",
            "$base/$token/abc/x.mp4",
            "$base/$token/0/x.mp4/more",
            "$base/$token",
            "$base/",
            "http://127.0.0.1:$port/",
            "http://127.0.0.1:$port/api/v1/info",
        )) {
            get(path) { r -> assertEquals(path, 404, r.code) }
        }
        get("$base/$token/0") { r -> assertEquals("name segment optional", 200, r.code) }
        get("$base/$token/0/any-name.bin") { r -> assertEquals("name not checked", 200, r.code) }
        assertTrue(s.activeConnections >= 0)
    }

    @Test
    fun namesWithSpacesAndUnicodeAreEncoded() {
        val (s, port) = start()
        val url = s.urlFor(1, "192.168.1.8")
        assertEquals("http://192.168.1.8:$port/c/$token/1/Holiday%20caf%C3%A9%20%F0%9F%8E%89%20%231.jpg", url)
        assertTrue(url.all { it.code in 0x21..0x7e })
        get(s.urlFor(1, "127.0.0.1")) { r -> assertEquals(200, r.code) }
    }

    @Test
    fun aNewServerHasANewRandomToken() {
        val a = CastFileServer(items, 0).also { servers += it }
        val b = CastFileServer(items, 0).also { servers += it }
        a.start()
        b.start()
        val ta = a.urlFor(0, "h").split('/')[4]
        val tb = b.urlFor(0, "h").split('/')[4]
        assertEquals(32, ta.length)
        assertTrue(ta != tb)
    }

    @Test
    fun takesTheNextPortWhenTheFirstIsBusy() {
        ServerSocket(0).use { busy ->
            val p = busy.localPort
            val (_, bound) = start(port = p)
            assertTrue("bound $bound, busy $p", bound in (p + 1)..(p + 9))
        }
    }
}
