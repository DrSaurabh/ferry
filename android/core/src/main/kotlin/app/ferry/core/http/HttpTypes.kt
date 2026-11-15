package app.ferry.core.http

/**
 * Types of [MiniHttpServer] (plan §3.2, design §7).
 *
 * [path] is the raw (still percent-encoded) path without the query; handlers split it themselves, so a `+` or
 * `%2F` inside a file name is never misread. [query] values are decoded (UTF-8).
 */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    /** lower-case keys */
    val headers: Map<String, String>,
    val body: ByteArray?,
    val remoteIp: String,
)

/** A file that can be served with ranges. [etag] is the bare value (the server adds the quotes). */
interface RangeSource {
    val length: Long
    val etag: String
    fun openAt(offset: Long): java.io.InputStream
}

/** One response per request; a second call throws IllegalStateException. */
interface HttpResponder {
    fun json(code: Int, body: String)
    fun empty(code: Int)

    /** GET/HEAD, 200/206/416, Accept-Ranges, ETag, If-Range. */
    fun file(req: HttpRequest, src: RangeSource, name: String, mime: String)
}

fun interface Handler {
    fun handle(req: HttpRequest, res: HttpResponder)
}

/** In-memory [RangeSource] (tests, small payloads). */
class BytesRangeSource(private val bytes: ByteArray, override val etag: String = "${bytes.size}") : RangeSource {
    override val length: Long get() = bytes.size.toLong()
    override fun openAt(offset: Long): java.io.InputStream =
        java.io.ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
}
