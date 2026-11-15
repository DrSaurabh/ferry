package app.ferry.core.it

import app.ferry.core.HostPort
import app.ferry.core.http.MiniHttpServer
import app.ferry.core.net.FerryClient
import app.ferry.core.net.FerryException
import app.ferry.core.net.FerryHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** [B15] A v1 PC answers /api/v1/info without "protocol". Stub: `MiniHttpServer` (Ph3a; Ph2a used a raw socket). */
class OldPcTest {
    @Test
    fun oldPcDetected(): Unit = runBlocking {
        val body = """{"app": "ferry", "version": "1.3.0", "name": "PC", "os": "windows", "free_bytes": 5}"""
        MiniHttpServer(0).use { server ->
            server.route("GET", "/api/v1/info") { _, res -> res.json(200, body) }
            val port = server.start()
            try {
                FerryClient(FerryHttp.newClient(null, "JVM test"), HostPort("127.0.0.1", port)).info()
                fail("expected OldPc")
            } catch (e: FerryException.OldPc) {
                assertEquals(1, e.protocol)
            }
        }
    }
}
