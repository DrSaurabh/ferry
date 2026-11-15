package app.ferry.core.cast

import app.ferry.core.FerryJson
import app.ferry.core.http.HttpRequest
import app.ferry.core.http.HttpResponder
import app.ferry.core.http.MiniHttpConfig
import app.ferry.core.http.MiniHttpServer
import app.ferry.core.model.CastRequest
import app.ferry.core.model.CastState
import app.ferry.core.model.ControlRequest
import kotlinx.serialization.KSerializer

/**
 * Implemented by the TV UI (design §5.5, plan §3.3). Called on server threads: forward to the UI through a
 * Channel/StateFlow and answer [state] from a @Volatile snapshot; never block on the main thread.
 * `false` from [onCast]/[onControl] = refused (the phone gets 409).
 */
interface CastSink {
    fun onCast(req: CastRequest, fromIp: String): Boolean
    fun onControl(c: ControlRequest): Boolean
    fun state(): CastState
}

/**
 * TV side of casting (design §5.5): [MiniHttpServer] on TCP [port] (8766) with
 * `POST /api/v1/cast`, `POST /api/v1/control`, `GET /api/v1/state`. Bodies are JSON ≤ 64 KB (413 above, by the
 * server); malformed or invalid requests → 400 and the sink is not called.
 */
class CastReceiverServer(
    private val sink: CastSink,
    private val port: Int = 8766,
    private val log: (String) -> Unit = {},
    config: MiniHttpConfig = MiniHttpConfig(),
) : java.io.Closeable {
    private val server = MiniHttpServer(port, config, 0, log)

    init {
        server.route("POST", CAST) { req, res -> exact(req, res, CAST) { cast(req, res) } }
        server.route("POST", CONTROL) { req, res -> exact(req, res, CONTROL) { control(req, res) } }
        server.route("GET", STATE) { req, res ->
            exact(req, res, STATE) {
                res.json(200, FerryJson.encodeToString(CastState.serializer(), sink.state()))
            }
        }
    }

    /** Starts the server and returns the bound port. */
    fun start(): Int = server.start()

    override fun close() = server.close()

    private fun cast(req: HttpRequest, res: HttpResponder) {
        val c = parse(req, res, CastRequest.serializer()) ?: return
        val valid = (c.kind == CastRequest.VIDEO || c.kind == CastRequest.PHOTOS) && c.items.isNotEmpty() &&
            c.start in c.items.indices && c.intervalMs > 0 &&
            c.items.all { it.url.startsWith("http://") && it.name.isNotEmpty() }
        if (!valid) return bad(res, "invalid cast request")
        log("cast ${c.kind} ×${c.items.size} from ${c.from} (${req.remoteIp})")
        answer(res, sink.onCast(c, req.remoteIp))
    }

    private fun control(req: HttpRequest, res: HttpResponder) {
        val c = parse(req, res, ControlRequest.serializer()) ?: return
        val valid = c.action in ControlRequest.ACTIONS &&
            (c.action != ControlRequest.SEEK || c.deltaMs != null) &&
            (c.action != ControlRequest.INTERVAL || (c.intervalMs != null && c.intervalMs > 0))
        if (!valid) return bad(res, "invalid control request")
        answer(res, sink.onControl(c))
    }

    private fun answer(res: HttpResponder, accepted: Boolean) {
        if (accepted) res.json(200, OK) else res.json(409, """{"result":"error","message":"refused"}""")
    }

    private fun <T> parse(req: HttpRequest, res: HttpResponder, s: KSerializer<T>): T? {
        val body = req.body
        if (body == null || body.isEmpty()) {
            bad(res, "empty body")
            return null
        }
        return try {
            FerryJson.decodeFromString(s, String(body, Charsets.UTF_8))
        } catch (e: Exception) {
            bad(res, "bad JSON")
            null
        }
    }

    private fun bad(res: HttpResponder, msg: String) =
        res.json(400, """{"result":"error","message":"$msg"}""")

    private inline fun exact(req: HttpRequest, res: HttpResponder, path: String, body: () -> Unit) {
        if (req.path != path) res.json(404, """{"result":"error","message":"Not Found"}""") else body()
    }

    companion object {
        const val CAST = "/api/v1/cast"
        const val CONTROL = "/api/v1/control"
        const val STATE = "/api/v1/state"
        private const val OK = """{"ok":true}"""
    }
}
