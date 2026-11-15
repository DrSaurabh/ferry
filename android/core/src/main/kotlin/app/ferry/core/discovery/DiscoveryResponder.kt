package app.ferry.core.discovery

import app.ferry.core.model.DiscoveryReply

/**
 * TV side of discovery (design §5.5): answers discovery requests with [identity] (`device_type "tv"`, port
 * 8766) plus the request's nonce, unicast to the sender. At most [maxPerSecond] replies per second in total
 * (cheap flood guard, like ferry.py). Thread "ferry-discovery" (daemon), 0.5 s receive timeout.
 *
 * **The loop never dies** (the [B2] lesson from the PC side): every receive and send is in try/catch; an
 * IOException (e.g. Windows' "connection reset" after replying to a closed port) is skipped. Only [close]
 * ends it.
 */
class DiscoveryResponder(
    private val transport: UdpTransport,
    private val identity: () -> DiscoveryReply,
    private val maxPerSecond: Int = 20,
    private val log: (String) -> Unit = {},
    /** Monotonic milliseconds (tests drive the rate limit with a fake). */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : java.io.Closeable {
    @Volatile private var closed = false
    private var thread: Thread? = null
    private val recent = ArrayDeque<Long>()

    /** Replies sent so far (diagnostics, tests). */
    @Volatile var replies = 0
        private set

    fun start() {
        check(thread == null) { "already started" }
        thread = Thread({ loop() }, "ferry-discovery").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        closed = true
        try {
            transport.close()
        } catch (e: Exception) {
            // already closed
        }
    }

    private fun loop() {
        val buf = ByteArray(2048)
        var lastError = ""
        while (!closed) {
            try {
                val d = transport.receive(buf, 500) ?: continue
                val req = DiscoveryCodec.parseRequest(buf, d.length) ?: continue
                if (!allow()) continue
                val reply = DiscoveryCodec.reply(identity().copy(nonce = req.nonce))
                transport.send(reply, d.address, d.port)
                replies++
            } catch (e: Exception) { // IOException, a bad identity(), anything: log once per kind and go on
                if (closed) break
                val msg = e.toString()
                if (msg != lastError) {
                    lastError = msg
                    log("discovery responder: $msg")
                }
                try {
                    Thread.sleep(10)
                } catch (ie: InterruptedException) {
                    // keep going until close()
                }
            }
        }
    }

    private fun allow(): Boolean {
        val now = nowMs()
        while (recent.isNotEmpty() && now - recent.first() >= 1_000) recent.removeFirst()
        if (recent.size >= maxPerSecond) return false
        recent.addLast(now)
        return true
    }
}
