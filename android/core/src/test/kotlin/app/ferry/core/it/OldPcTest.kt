package app.ferry.core.it

import app.ferry.core.HostPort
import app.ferry.core.net.FerryClient
import app.ferry.core.net.FerryException
import app.ferry.core.net.FerryHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket

/**
 * [B15] A v1 PC answers /api/v1/info without "protocol". Stub: a tiny ServerSocket server (until
 * MiniHttpServer exists, Ph3a).
 */
class OldPcTest {
    @Test
    fun oldPcDetected(): Unit = runBlocking {
        val body = """{"app": "ferry", "version": "1.3.0", "name": "PC", "os": "windows", "free_bytes": 5}""".toByteArray()
        ServerSocket(0).use { server ->
            Thread({
                try {
                    server.accept().use { s ->
                        val r = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        while (true) {
                            val line = r.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val head = "HTTP/1.0 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n\r\n"
                        s.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); write(body); flush() }
                    }
                } catch (e: Exception) {
                    // test ends
                }
            }, "old-pc-stub").apply { isDaemon = true; start() }
            try {
                FerryClient(FerryHttp.newClient(null, "JVM test"), HostPort("127.0.0.1", server.localPort)).info()
                fail("expected OldPc")
            } catch (e: FerryException.OldPc) {
                assertEquals(1, e.protocol)
            }
        }
    }
}
