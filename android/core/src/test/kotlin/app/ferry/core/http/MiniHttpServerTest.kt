package app.ferry.core.http

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.random.Random

/** `MiniHttpServer` on loopback (plan §3.7, design §7 [B12]); requests are written by hand on raw sockets. */
class MiniHttpServerTest {
    private val servers = mutableListOf<MiniHttpServer>()
    private val sockets = mutableListOf<Socket>()
    private val bytes = Random(7).nextBytes(100_000)
    private val file = BytesRangeSource(bytes, "100000-42")

    @After
    fun tearDown() {
        sockets.forEach { runCatching { it.close() } }
        servers.forEach { it.close() }
    }

    private fun server(config: MiniHttpConfig = MiniHttpConfig(), setup: (MiniHttpServer) -> Unit = {}): Pair<MiniHttpServer, Int> {
        val s = MiniHttpServer(0, config, log = { println("[server] $it") })
        servers += s
        s.route("GET", "/f") { req, res -> res.file(req, file, "pic ü.jpg", "image/jpeg") }
        s.route("POST", "/api/echo") { req, res ->
            res.json(200, """{"n":${req.body?.size ?: -1},"q":"${req.query["q"]}"}""")
        }
        setup(s)
        return s to s.start()
    }

    class Reply(val code: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun connect(port: Int, rcvBuf: Int? = null): Socket {
        val s = Socket()
        sockets += s
        if (rcvBuf != null) s.receiveBufferSize = rcvBuf
        s.connect(InetSocketAddress("127.0.0.1", port), 5_000)
        s.soTimeout = 10_000
        return s
    }

    private fun request(port: Int, text: String, body: ByteArray = ByteArray(0)): Reply {
        val s = connect(port)
        s.getOutputStream().apply { write(text.toByteArray(Charsets.ISO_8859_1)); write(body); flush() }
        return readReply(s.getInputStream())
    }

    private fun get(port: Int, path: String, vararg headers: String, method: String = "GET"): Reply =
        request(port, "$method $path HTTP/1.1\r\nHost: x\r\n" + headers.joinToString("") { "$it\r\n" } + "\r\n")

    private fun readReply(input: InputStream): Reply {
        val all = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = try {
                input.read(buf)
            } catch (e: IOException) {
                if (all.size() > 0) -1 else throw e
            }
            if (n < 0) break
            all.write(buf, 0, n)
        }
        val raw = all.toByteArray()
        val text = String(raw, Charsets.ISO_8859_1)
        val end = text.indexOf("\r\n\r\n")
        assertTrue("no header end in: ${text.take(200)}", end > 0)
        val lines = text.substring(0, end).split("\r\n")
        val code = lines[0].split(' ')[1].toInt()
        val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        return Reply(code, headers, raw.copyOfRange(end + 4, raw.size))
    }

    private fun waitFor(timeoutMs: Long, what: String, cond: () -> Boolean): Long {
        val t0 = System.currentTimeMillis()
        while (!cond()) {
            if (System.currentTimeMillis() - t0 > timeoutMs) throw AssertionError("timed out after $timeoutMs ms: $what")
            Thread.sleep(50)
        }
        return System.currentTimeMillis() - t0
    }

    @Test
    fun getAndHead() {
        val (_, port) = server()
        val r = get(port, "/f")
        assertEquals(200, r.code)
        assertArrayEquals(bytes, r.body)
        assertEquals("100000", r.headers["content-length"])
        assertEquals("bytes", r.headers["accept-ranges"])
        assertEquals("\"100000-42\"", r.headers["etag"])
        assertEquals("image/jpeg", r.headers["content-type"])
        assertEquals("close", r.headers["connection"])
        assertEquals("inline; filename*=UTF-8''pic%20%C3%BC.jpg", r.headers["content-disposition"])
        val h = get(port, "/f", method = "HEAD")
        assertEquals(200, h.code)
        assertEquals("100000", h.headers["content-length"])
        assertEquals(0, h.body.size)
    }

    @Test
    fun ranges() {
        val (_, port) = server()
        val ab = get(port, "/f", "Range: bytes=10-19")
        assertEquals(206, ab.code)
        assertEquals("bytes 10-19/100000", ab.headers["content-range"])
        assertArrayEquals(bytes.copyOfRange(10, 20), ab.body)
        val open = get(port, "/f", "Range: bytes=99990-")
        assertEquals(206, open.code)
        assertArrayEquals(bytes.copyOfRange(99_990, 100_000), open.body)
        val suffix = get(port, "/f", "Range: bytes=-5")
        assertEquals(206, suffix.code)
        assertEquals("bytes 99995-99999/100000", suffix.headers["content-range"])
        assertArrayEquals(bytes.copyOfRange(99_995, 100_000), suffix.body)
        val multi = get(port, "/f", "Range: bytes=0-1,5-6")
        assertEquals(200, multi.code)
        assertArrayEquals(bytes, multi.body)
        val out = get(port, "/f", "Range: bytes=100000-")
        assertEquals(416, out.code)
        assertEquals("bytes */100000", out.headers["content-range"])
        assertEquals(0, out.body.size)
        val headRange = get(port, "/f", "Range: bytes=0-99", method = "HEAD")
        assertEquals(206, headRange.code)
        assertEquals("100", headRange.headers["content-length"])
        assertEquals(0, headRange.body.size)
    }

    @Test
    fun ifRange() {
        val (_, port) = server()
        val match = get(port, "/f", "Range: bytes=0-9", "If-Range: \"100000-42\"")
        assertEquals(206, match.code)
        assertArrayEquals(bytes.copyOfRange(0, 10), match.body)
        val mismatch = get(port, "/f", "Range: bytes=0-9", "If-Range: \"99-1\"")
        assertEquals(200, mismatch.code)
        assertArrayEquals(bytes, mismatch.body)
    }

    @Test
    fun requestLineTooLong() {
        val (_, port) = server()
        val r = get(port, "/f?x=" + "a".repeat(9 * 1024))
        assertTrue("code ${r.code}", r.code == 414 || r.code == 400)
    }

    @Test
    fun headersTooLarge() {
        val (_, port) = server()
        val many = (1..40).map { "X-Pad-$it: " + "b".repeat(500) }.toTypedArray()
        val r = get(port, "/f", *many)
        assertTrue("code ${r.code}", r.code == 431 || r.code == 400)
    }

    @Test
    fun jsonBodyLimit() {
        val (_, port) = server()
        val ok = request(port, "POST /api/echo?q=a%20b%E2%80%99 HTTP/1.1\r\nContent-Length: 5\r\n\r\n", "hello".toByteArray())
        assertEquals(200, ok.code)
        assertEquals("""{"n":5,"q":"a b’"}""", String(ok.body, Charsets.UTF_8))
        val big = ByteArray(64 * 1024 + 1)
        val r = request(port, "POST /api/echo HTTP/1.1\r\nContent-Length: ${big.size}\r\n\r\n", big)
        assertEquals(413, r.code)
        val noLength = request(port, "POST /api/echo HTTP/1.1\r\n\r\n")
        assertEquals(411, noLength.code)
    }

    @Test
    fun unknownRouteIs404() {
        val (_, port) = server()
        assertEquals(404, get(port, "/nothing").code)
        assertEquals(404, request(port, "DELETE /f HTTP/1.1\r\n\r\n").code)
    }

    @Test
    fun handlerExceptionIs500() {
        val (_, port) = server { it.route("GET", "/boom") { _, _ -> throw IllegalStateException("boom") } }
        assertEquals(500, get(port, "/boom").code)
        assertEquals(200, get(port, "/f").code)
    }

    @Test
    fun sixteenSlowClientsServedSeventeenthGets503() {
        val (s, port) = server()
        val slow = (1..16).map { connect(port).also { c -> c.getOutputStream().write("GET /f HTTP/1.1\r\nHost: x\r\n".toByteArray()) } }
        waitFor(5_000, "16 active") { s.activeConnections == 16 }
        val busy = get(port, "/f")
        assertEquals(503, busy.code)
        for (c in slow) c.getOutputStream().write("\r\n".toByteArray())
        for (c in slow) {
            val r = readReply(c.getInputStream())
            assertEquals(200, r.code)
            assertArrayEquals(bytes, r.body)
        }
        waitFor(5_000, "slots freed") { s.activeConnections == 0 }
        assertEquals(200, get(port, "/f").code)
    }

    @Test
    fun stalledReaderSlotFreedWithin35s() { // [B12]
        val size = 50L shl 20
        val big = object : RangeSource {
            override val length = size
            override val etag = "big"
            override fun openAt(offset: Long): InputStream = object : InputStream() {
                var pos = offset
                override fun read(): Int = if (pos < size) (pos++ and 0xff).toInt() else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (pos >= size) return -1
                    val n = minOf(len.toLong(), size - pos).toInt()
                    pos += n
                    return n
                }
            }
        }
        val (s, port) = server { it.route("GET", "/big") { req, res -> res.file(req, big, "big.bin", "application/octet-stream") } }
        val c = connect(port, rcvBuf = 4096)
        c.getOutputStream().write("GET /big HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        val got = c.getInputStream().read(ByteArray(1024))
        assertTrue(got > 0)
        assertEquals(1, s.activeConnections)
        // The client now reads nothing more; the server's write blocks once the socket buffers are full.
        val ms = waitFor(40_000, "stalled slot freed") { s.activeConnections == 0 }
        println("FERRY_NOTE stalledReaderSlotFreedWithin35s: slot freed after $ms ms")
        assertTrue("slot freed after $ms ms", ms <= 35_000)
        assertEquals(200, get(port, "/f").code)
    }

    @Test
    fun readTimeoutClosesIdleSockets() {
        val (s, port) = server(MiniHttpConfig(readTimeoutMs = 500))
        val c = connect(port)
        waitFor(2_000, "accepted") { s.activeConnections == 1 }
        val t0 = System.currentTimeMillis()
        val n = try {
            c.getInputStream().read()
        } catch (e: SocketTimeoutException) {
            throw AssertionError("server did not close the idle socket within 10 s")
        } catch (e: IOException) {
            -1 // reset is also "closed"
        }
        assertEquals(-1, n)
        assertTrue(System.currentTimeMillis() - t0 < 5_000)
        waitFor(2_000, "slot freed") { s.activeConnections == 0 }
    }

    @Test
    fun portFallback() {
        ServerSocket().use { taken ->
            taken.bind(InetSocketAddress(0))
            val p = taken.localPort
            val s = MiniHttpServer(p, portFallback = 5)
            servers += s
            s.route("GET", "/f") { req, res -> res.file(req, file, "f", "image/jpeg") }
            val bound = s.start()
            assertTrue("bound $bound for taken $p", bound in (p + 1)..(p + 5))
            assertEquals(200, get(bound, "/f").code)
        }
    }

    @Test
    fun parseQueryDecodesUtf8() {
        val q = MiniHttpServer.parseQuery("name=Holiday+%2B+caf%C3%A9.jpg&x=1&x=2&bad=%zz&flag")
        assertEquals("Holiday + café.jpg", q["name"])
        assertEquals("1", q["x"])
        assertNull(q["bad"])
        assertEquals("", q["flag"])
    }
}
