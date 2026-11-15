package app.ferry.core.cast

import app.ferry.core.http.Handler
import app.ferry.core.http.MiniHttpConfig
import app.ferry.core.http.MiniHttpServer
import app.ferry.core.http.RangeSource
import app.ferry.core.util.PercentEncode
import app.ferry.core.util.Tokens

/** One file offered to the TV; `:app` backs [source] with `ContentRangeSource` (MediaStore). */
data class CastFile(val name: String, val mime: String, val source: RangeSource)

/**
 * Phone side of casting (design §6): serves only [items] at `/c/<token>/<index>/<percent-encoded name>`,
 * GET/HEAD with Range (via [MiniHttpServer], incl. the write watchdog [B12]). [token] is 128 random bits per
 * cast session; a wrong token, a bad or out-of-range index → 404. The name segment is only there so players
 * see a file name; it is not checked.
 *
 * Binds [port] (8767) or one of the next [portFallback] ports.
 */
class CastFileServer(
    private val items: List<CastFile>,
    private val port: Int = 8767,
    private val token: String = Tokens.random128(),
    private val portFallback: Int = 9,
    private val log: (String) -> Unit = {},
    config: MiniHttpConfig = MiniHttpConfig(),
) : java.io.Closeable {
    private val server = MiniHttpServer(port, config, portFallback, log)
    @Volatile private var boundPort = 0

    init {
        val h = Handler { req, res ->
            val item = find(req.path)
            if (item == null) {
                res.json(404, """{"result":"error","message":"Not Found"}""")
            } else {
                res.file(req, item.source, item.name, item.mime)
            }
        }
        server.route("GET", "/c/", h) // HEAD matches GET routes
    }

    /** Starts the server and returns the bound port. */
    fun start(): Int = server.start().also { boundPort = it }

    /** `http://<phoneIp>:<port>/c/<token>/<index>/<name>`; call after [start]. */
    fun urlFor(index: Int, phoneIp: String): String {
        check(boundPort != 0) { "start() first" }
        require(index in items.indices) { "no item $index" }
        return "http://$phoneIp:$boundPort/c/$token/$index/" + PercentEncode.utf8(items[index].name)
    }

    val activeConnections: Int get() = server.activeConnections

    override fun close() = server.close()

    /** `/c/<token>/<i>[/<name>]` → item, else null. */
    private fun find(path: String): CastFile? {
        val parts = path.removePrefix("/c/").split('/')
        if (parts.size < 2 || parts.size > 3 || parts[0] != token) return null
        val i = parts[1]
        if (i.isEmpty() || i.length > 6 || !i.all { it in '0'..'9' }) return null
        return items.getOrNull(i.toInt())
    }
}
