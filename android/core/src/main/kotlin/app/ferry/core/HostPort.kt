package app.ferry.core

/** A PC (or TV) address: IPv4 host + TCP port [B16]. */
data class HostPort(val host: String, val port: Int = DEFAULT_PORT) {
    override fun toString(): String = if (port == DEFAULT_PORT) host else "$host:$port"

    companion object {
        const val DEFAULT_PORT = 8765

        /**
         * Parses what a user types or what was stored: "192.168.1.5", "192.168.1.5:8800", with surrounding
         * spaces, an optional "http://" and a trailing "/" (a pasted URL). IPv4 only; anything else → null.
         */
        fun parse(text: String): HostPort? {
            var t = text.trim()
            if (t.startsWith("http://", ignoreCase = true)) t = t.substring(7)
            t = t.trimEnd('/')
            if (t.isEmpty()) return null
            val colon = t.indexOf(':')
            val hostPart = if (colon >= 0) t.substring(0, colon) else t
            val port = if (colon >= 0) {
                val p = t.substring(colon + 1)
                if (p.isEmpty() || p.length > 5 || !p.all { it in '0'..'9' }) return null
                p.toInt()
            } else DEFAULT_PORT
            if (port !in 1..65535) return null
            val parts = hostPart.split('.')
            if (parts.size != 4) return null
            for (s in parts) {
                if (s.isEmpty() || s.length > 3 || !s.all { it in '0'..'9' } || s.toInt() > 255) return null
            }
            return HostPort(parts.joinToString(".") { it.toInt().toString() }, port)
        }
    }
}
