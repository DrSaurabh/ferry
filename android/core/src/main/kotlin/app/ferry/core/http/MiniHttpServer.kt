package app.ferry.core.http

import app.ferry.core.util.PercentEncode
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class MiniHttpConfig(
    val maxConnections: Int = 16,
    val readTimeoutMs: Int = 30_000,
    val writeStallMs: Long = 30_000,
    val maxRequestLine: Int = 8 * 1024,
    val maxHeaderBytes: Int = 16 * 1024,
    val maxJsonBody: Int = 64 * 1024,
    val watchdogTickMs: Long = 1_000,
)

/**
 * Small blocking HTTP/1.1 server (design §7, plan §3.3): one accept thread, one pooled thread per connection
 * (at most [MiniHttpConfig.maxConnections]; the next one gets 503), `Connection: close` after every response,
 * a socket read timeout, size limits on the request line, headers and bodies, and a **write watchdog** [B12]:
 * a socket whose write has made no progress for [MiniHttpConfig.writeStallMs] is closed, which unblocks the
 * writer and frees its slot (a paused or vanished TV must not use up the slots).
 * All threads are daemon threads named `ferry-http-*`.
 */
class MiniHttpServer(
    private val port: Int,
    private val config: MiniHttpConfig = MiniHttpConfig(),
    private val portFallback: Int = 0,
    private val log: (String) -> Unit = {},
) : Closeable {
    private class Route(val method: String, val prefix: String, val handler: Handler)

    /** One live connection; [writingSinceNs] > 0 while a write call is blocked. */
    private class Conn(val socket: Socket) {
        @Volatile var writingSinceNs = 0L
    }

    private val routes = CopyOnWriteArrayList<Route>()
    private val conns = ConcurrentHashMap.newKeySet<Conn>()
    private val active = AtomicInteger()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var closed = false
    private var pool: ThreadPoolExecutor? = null

    val activeConnections: Int get() = active.get()

    /** Registers [handler] for [method] (HEAD also matches GET routes) and paths starting with [pathPrefix]. */
    fun route(method: String, pathPrefix: String, handler: Handler) {
        check(server == null) { "route() must be called before start()" }
        routes += Route(method.uppercase(), pathPrefix, handler)
    }

    /** Binds port..port+portFallback (0 = any free port) and returns the bound port. */
    fun start(): Int {
        check(server == null) { "already started" }
        var last: IOException? = null
        for (p in port..(if (port == 0) 0 else port + portFallback)) {
            val s = ServerSocket()
            try {
                s.bind(InetSocketAddress(p), 50)
                server = s
                break
            } catch (e: IOException) {
                last = e
                closeQuietly(s)
            }
        }
        val s = server ?: throw (last ?: IOException("no port"))
        // The slot limit is the [active] counter; the pool itself is unbounded so that a thread that has just
        // freed its slot but not yet returned to the pool can never cause a rejection.
        pool = ThreadPoolExecutor(0, Int.MAX_VALUE, 30, TimeUnit.SECONDS, SynchronousQueue(), threads("ferry-http-conn"))
        threads("ferry-http-accept").newThread { acceptLoop(s) }.start()
        threads("ferry-http-watchdog").newThread { watchdogLoop() }.start()
        log("http server on port ${s.localPort}")
        return s.localPort
    }

    override fun close() {
        closed = true
        server?.let { closeQuietly(it) }
        for (c in conns) closeQuietly(c.socket)
        pool?.shutdownNow()
    }

    // ------------------------------------------------------------------ accept / watchdog

    private fun acceptLoop(s: ServerSocket) {
        while (!closed) {
            val sock = try {
                s.accept()
            } catch (e: IOException) {
                if (closed) return
                log("accept: $e")
                sleepQuietly(100)
                continue
            }
            try {
                if (active.incrementAndGet() > config.maxConnections) {
                    active.decrementAndGet()
                    reject(sock)
                    continue
                }
                val c = Conn(sock)
                conns += c
                pool!!.execute { serve(c) }
            } catch (e: Throwable) { // RejectedExecutionException after close(), or anything else: never die
                active.decrementAndGet()
                closeQuietly(sock)
                if (!closed) log("accept handling: $e")
            }
        }
    }

    /** 503 on a short-lived thread (the client's request is drained so the reply is not lost to a reset). */
    private fun reject(sock: Socket) {
        threads("ferry-http-busy").newThread {
            try {
                sock.soTimeout = 2_000
                val out = sock.getOutputStream()
                out.write(head(503, "application/json; charset=utf-8", BUSY.size.toLong(), emptyList()))
                out.write(BUSY)
                out.flush()
            } catch (e: IOException) {
                // client gone
            } finally {
                lingerClose(sock)
            }
        }.start()
    }

    private fun watchdogLoop() {
        val stallNs = config.writeStallMs * 1_000_000
        while (!closed) {
            sleepQuietly(config.watchdogTickMs)
            val now = System.nanoTime()
            for (c in conns) {
                val since = c.writingSinceNs
                if (since != 0L && now - since > stallNs) {
                    log("write stalled ${config.writeStallMs / 1000} s: closing ${c.socket.inetAddress?.hostAddress}")
                    closeQuietly(c.socket)
                }
            }
        }
    }

    // ------------------------------------------------------------------ one connection

    private class HttpError(val code: Int) : Exception()

    private fun serve(c: Conn) {
        val sock = c.socket
        var consumed = false
        try {
            sock.soTimeout = config.readTimeoutMs
            sock.tcpNoDelay = true
            val input = BufferedInputStream(sock.getInputStream(), 8 * 1024)
            val out = WatchedOutput(sock.getOutputStream(), c)
            val res = Responder(out)
            val req = try {
                readRequest(input, sock).also { consumed = true }
            } catch (e: HttpError) {
                res.json(e.code, errorBody(e.code))
                return
            } ?: return // connection closed before a request line
            val route = find(req)
            if (route == null) {
                res.json(404, errorBody(404))
                return
            }
            try {
                route.handler.handle(req, res)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                log("handler ${req.method} ${req.path}: $e")
                if (!res.sent) res.json(500, errorBody(500))
            }
            if (!res.sent) res.empty(204)
        } catch (e: IOException) {
            // read timeout, client gone, or closed by the watchdog: the slot is freed below
        } catch (e: Throwable) {
            log("connection: $e")
        } finally {
            if (consumed) closeQuietly(sock) else lingerClose(sock)
            conns -= c
            active.decrementAndGet()
        }
    }

    private fun find(req: HttpRequest): Route? {
        val m = if (req.method == "HEAD") "GET" else req.method
        return routes.filter { (it.method == req.method || it.method == m) && req.path.startsWith(it.prefix) }
            .maxByOrNull { it.prefix.length }
    }

    /** null = the client closed without sending anything. Throws [HttpError] for bad requests. */
    private fun readRequest(input: InputStream, sock: Socket): HttpRequest? {
        var line = readLine(input, config.maxRequestLine, 414) ?: return null
        while (line.isEmpty()) line = readLine(input, config.maxRequestLine, 414) ?: return null // stray CRLF
        val parts = line.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/")) throw HttpError(400)
        val method = parts[0].uppercase()
        val target = parts[1]
        val headers = HashMap<String, String>()
        var headerBytes = 0
        while (true) {
            val h = readLine(input, config.maxHeaderBytes, 431) ?: throw HttpError(400)
            if (h.isEmpty()) break
            headerBytes += h.length + 2
            if (headerBytes > config.maxHeaderBytes) throw HttpError(431)
            val colon = h.indexOf(':')
            if (colon <= 0) throw HttpError(400)
            val k = h.substring(0, colon).trim().lowercase()
            val v = h.substring(colon + 1).trim()
            headers[k] = headers[k]?.let { "$it, $v" } ?: v
        }
        var body: ByteArray? = null
        val cl = headers["content-length"]
        if (headers.containsKey("transfer-encoding")) throw HttpError(411)
        if (cl != null) {
            val n = cl.toLongOrNull() ?: throw HttpError(400)
            if (n < 0) throw HttpError(400)
            if (n > config.maxJsonBody) throw HttpError(413)
            body = readExactly(input, n.toInt())
        } else if (method == "POST" || method == "PUT") {
            throw HttpError(411)
        }
        val q = target.indexOf('?')
        val path = if (q < 0) target else target.substring(0, q)
        val query = if (q < 0) emptyMap() else parseQuery(target.substring(q + 1))
        val ip = sock.inetAddress?.hostAddress ?: ""
        return HttpRequest(method, path, query, headers, body, ip)
    }

    /** ISO-8859-1 line without CRLF; null on EOF before any byte; [HttpError] ([tooLong]) past [limit] bytes. */
    private fun readLine(input: InputStream, limit: Int, tooLong: Int): String? {
        val sb = StringBuilder()
        var n = 0
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (n == 0) return null
                throw HttpError(400)
            }
            if (b == '\n'.code) break
            if (++n > limit) throw HttpError(tooLong)
            sb.append(b.toChar())
        }
        if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
        return sb.toString()
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(buf, got, n - got)
            if (r < 0) throw IOException("body ended after $got of $n bytes")
            got += r
        }
        return buf
    }

    // ------------------------------------------------------------------ responses

    /** Writes in ≤ 64 KB pieces and marks the connection as "writing" for the watchdog while a write blocks. */
    private class WatchedOutput(private val out: OutputStream, private val c: Conn) {
        fun write(b: ByteArray, off: Int = 0, len: Int = b.size) {
            var o = off
            var left = len
            while (left > 0) {
                val n = minOf(left, 64 * 1024)
                c.writingSinceNs = System.nanoTime().let { if (it == 0L) 1L else it }
                try {
                    out.write(b, o, n)
                } finally {
                    c.writingSinceNs = 0L
                }
                o += n
                left -= n
            }
        }

        fun flush() {
            c.writingSinceNs = System.nanoTime().let { if (it == 0L) 1L else it }
            try {
                out.flush()
            } finally {
                c.writingSinceNs = 0L
            }
        }
    }

    private inner class Responder(private val out: WatchedOutput) : HttpResponder {
        var sent = false
            private set

        private fun begin() {
            check(!sent) { "response already sent" }
            sent = true
        }

        override fun json(code: Int, body: String) {
            begin()
            val b = body.toByteArray(Charsets.UTF_8)
            out.write(head(code, "application/json; charset=utf-8", b.size.toLong(), emptyList()))
            out.write(b)
            out.flush()
        }

        override fun empty(code: Int) {
            begin()
            out.write(head(code, null, 0, emptyList()))
            out.flush()
        }

        override fun file(req: HttpRequest, src: RangeSource, name: String, mime: String) {
            begin()
            val size = src.length
            val etag = "\"${src.etag}\""
            val ifRange = req.headers["if-range"]?.trim()
            val r = if (ifRange == null || ifRange == etag || ifRange == src.etag) {
                RangeParser.parse(req.headers["range"], size)
            } else {
                RangeParser.R.Whole // validator changed: send the whole file
            }
            val common = listOf("Accept-Ranges" to "bytes", "ETag" to etag)
            if (r is RangeParser.R.Invalid) {
                out.write(head(416, null, 0, common + ("Content-Range" to "bytes */$size")))
                out.flush()
                return
            }
            val start: Long
            val length: Long
            val extra = ArrayList(common)
            extra += "Content-Disposition" to "inline; filename*=UTF-8''" + PercentEncode.utf8(name)
            val code = if (r is RangeParser.R.Span) {
                start = r.start
                length = r.end - r.start + 1
                extra += "Content-Range" to "bytes ${r.start}-${r.end}/$size"
                206
            } else {
                start = 0
                length = size
                200
            }
            out.write(head(code, mime, length, extra))
            if (req.method == "HEAD") {
                out.flush()
                return
            }
            src.openAt(start).use { input ->
                val buf = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("source ended early") // closes the socket: the client sees a short body
                    out.write(buf, 0, n)
                    left -= n
                }
            }
            out.flush()
        }
    }

    private fun head(code: Int, contentType: String?, length: Long, extra: List<Pair<String, String>>): ByteArray {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(REASONS[code] ?: "Status").append("\r\n")
        if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(length).append("\r\n")
        for ((k, v) in extra) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun errorBody(code: Int) = """{"result":"error","message":"${REASONS[code] ?: "error"}"}"""

    // ------------------------------------------------------------------ helpers

    /** Half-close, then read and drop what the client is still sending (≤ 256 KB, ≤ 1 s), so it can read our reply. */
    private fun lingerClose(sock: Socket) {
        try {
            sock.shutdownOutput()
            sock.soTimeout = 1_000
            val input = sock.getInputStream()
            val buf = ByteArray(8 * 1024)
            var total = 0
            while (total < 256 * 1024) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
            }
        } catch (e: IOException) {
            // timeout or reset: close anyway
        } finally {
            closeQuietly(sock)
        }
    }

    companion object {
        private val BUSY = """{"result":"error","message":"busy"}""".toByteArray(Charsets.UTF_8)

        private val REASONS = mapOf(
            200 to "OK", 204 to "No Content", 206 to "Partial Content", 400 to "Bad Request", 403 to "Forbidden",
            404 to "Not Found", 405 to "Method Not Allowed", 409 to "Conflict", 411 to "Length Required",
            413 to "Payload Too Large", 414 to "URI Too Long", 416 to "Range Not Satisfiable",
            431 to "Request Header Fields Too Large", 500 to "Internal Server Error", 503 to "Service Unavailable",
        )

        /** `a=1&b=x%20y` → decoded map (UTF-8, `+` = space); the first value of a repeated key wins. */
        fun parseQuery(q: String): Map<String, String> {
            val m = LinkedHashMap<String, String>()
            for (part in q.split('&')) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                val k = decode(if (eq < 0) part else part.substring(0, eq))
                val v = if (eq < 0) "" else decode(part.substring(eq + 1))
                if (k != null && v != null && !m.containsKey(k)) m[k] = v
            }
            return m
        }

        private fun decode(s: String): String? = try {
            java.net.URLDecoder.decode(s, "UTF-8")
        } catch (e: IllegalArgumentException) {
            null
        }

        private fun threads(name: String) = ThreadFactory { r -> Thread(r, name).apply { isDaemon = true } }

        private fun sleepQuietly(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun closeQuietly(c: Closeable) {
            try {
                c.close()
            } catch (e: IOException) {
                // ignore
            }
        }
    }
}
