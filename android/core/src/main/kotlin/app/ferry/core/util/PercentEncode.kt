package app.ferry.core.util

/**
 * UTF-8 percent-encoding of everything except RFC 3986 unreserved characters. Used for the
 * `X-Ferry-Device` header (OkHttp refuses non-ASCII header values; ferry.py decodes with `unquote`) [B10]
 * and for query values (`name=`), so '+', '%', '&' and non-ASCII always reach the PC unchanged.
 */
object PercentEncode {
    private val HEX = "0123456789ABCDEF".toCharArray()

    fun utf8(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            val c = b.toInt() and 0xff
            if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
            ) {
                sb.append(c.toChar())
            } else {
                sb.append('%').append(HEX[c ushr 4]).append(HEX[c and 0x0f])
            }
        }
        return sb.toString()
    }
}
