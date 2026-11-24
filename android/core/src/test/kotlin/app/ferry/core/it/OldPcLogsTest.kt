package app.ferry.core.it

import app.ferry.core.HostPort
import app.ferry.core.http.MiniHttpServer
import app.ferry.core.net.FerryClient
import app.ferry.core.net.FerryException
import app.ferry.core.net.FerryHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** 2.1 M8 / M15: a 2.0 PC answers 404 to `POST /api/v1/logs` and has no `time_ms` in info. */
class OldPcLogsTest {
    @Test
    fun logsUnsupportedOn404(): Unit = runBlocking {
        val info = """{"app":"ferry","protocol":2,"version":"2.0.0","name":"PC","capabilities":["receive"]}"""
        MiniHttpServer(0).use { server ->
            server.route("GET", "/api/v1/info") { _, res -> res.json(200, info) }
            server.route("POST", "/api/v1/logs") { _, res -> res.json(404, """{"result":"error","message":"That page doesn't exist."}""") }
            val port = server.start()
            val c = FerryClient(FerryHttp.newClient(null, "JVM test"), HostPort("127.0.0.1", port))
            assertNull(c.info().timeMs)
            try {
                c.postLogs(1, "2026-11-24 02:01:03.456 #1 s=7f3a tap id=send\n")
                fail("expected LogsUnsupported")
            } catch (e: FerryException.LogsUnsupported) {
                // expected
            }
        }
    }
}
