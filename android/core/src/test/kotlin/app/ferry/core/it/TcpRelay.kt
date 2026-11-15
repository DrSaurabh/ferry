package app.ferry.core.it

import app.ferry.core.HostPort
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Loopback TCP forwarder between the client under test and ferry.py (plan §3.6 [PB8]). "The PC goes away" is a
 * mode switch here, not a kill + rebind of ferry.py (Windows binds with SO_EXCLUSIVEADDRUSE):
 * - [Mode.PASS]: forward both ways.
 * - [Mode.RESET]: every open connection is reset, new ones are accepted and reset at once (the PC is gone).
 * - [Mode.BLACKHOLE]: a silent stall like a real Wi-Fi drop: nothing is forwarded any more on open connections,
 *   new ones are accepted and never answered; no RST. A stalled connection stays dead after the switch back.
 */
class TcpRelay(private val target: HostPort) : Closeable {
    enum class Mode { PASS, RESET, BLACKHOLE }

    private class Conn(val client: Socket, val upstream: Socket) {
        @Volatile var stalled = false
    }

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val conns = CopyOnWriteArrayList<Conn>()
    @Volatile private var closed = false

    @Volatile var mode: Mode = Mode.PASS
        set(v) {
            field = v
            when (v) {
                Mode.RESET -> for (c in conns) { reset(c.client); reset(c.upstream) }
                Mode.BLACKHOLE -> for (c in conns) c.stalled = true
                Mode.PASS -> {}
            }
        }

    val base: HostPort get() = HostPort("127.0.0.1", server.localPort)

    /** Connections accepted so far (any mode). */
    @Volatile var accepted = 0
        private set

    init {
        thread("relay-accept") {
            while (!closed) {
                val c = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                accepted++
                handle(c)
            }
        }
    }

    private fun handle(c: Socket) {
        when (mode) {
            Mode.RESET -> reset(c)
            Mode.BLACKHOLE -> thread("relay-blackhole") { drain(c) }
            Mode.PASS -> {
                val up = try {
                    Socket(target.host, target.port)
                } catch (e: IOException) {
                    reset(c)
                    return
                }
                val conn = Conn(c, up)
                conns += conn
                thread("relay-up") { pump(conn, c, up) }
                thread("relay-down") { pump(conn, up, c) }
            }
        }
    }

    private fun pump(conn: Conn, from: Socket, to: Socket) {
        val buf = ByteArray(64 * 1024)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (conn.stalled || mode != Mode.PASS) {
                    conn.stalled = true // read and drop: the other side never hears of it
                    continue
                }
                output.write(buf, 0, n)
            }
        } catch (e: IOException) {
            // closed or reset
        } finally {
            if (conn.stalled && from === conn.upstream) {
                // The PC finished or closed its side, but the stalled client must not hear of it (no FIN, no RST):
                // a download stall stays silent until the client gives up (Ph3a). The client side's pump closes both.
                quietClose(conn.upstream)
            } else {
                quietClose(conn.client)
                quietClose(conn.upstream)
                conns -= conn
            }
        }
    }

    private fun drain(c: Socket) {
        try {
            val input = c.getInputStream()
            val buf = ByteArray(64 * 1024)
            while (input.read(buf) >= 0) { /* never answer */ }
        } catch (e: IOException) {
            // client gave up
        } finally {
            quietClose(c)
        }
    }

    override fun close() {
        closed = true
        quietClose(server)
        for (c in conns) { quietClose(c.client); quietClose(c.upstream) }
    }

    private companion object {
        fun thread(name: String, body: () -> Unit) = Thread(body, name).apply { isDaemon = true; start() }

        fun reset(s: Socket) {
            try {
                s.setSoLinger(true, 0) // RST instead of FIN
            } catch (e: IOException) {
                // already closed
            }
            quietClose(s)
        }

        fun quietClose(c: Closeable) {
            try {
                c.close()
            } catch (e: IOException) {
                // ignore
            }
        }
    }
}
