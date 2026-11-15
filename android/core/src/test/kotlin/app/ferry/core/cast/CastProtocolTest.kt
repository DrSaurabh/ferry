package app.ferry.core.cast

import app.ferry.core.FerryJson
import app.ferry.core.HostPort
import app.ferry.core.model.CastItem
import app.ferry.core.model.CastRequest
import app.ferry.core.model.CastState
import app.ferry.core.model.ControlRequest
import app.ferry.core.net.FerryException
import app.ferry.core.net.FerryHttp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.Collections

/** `CastReceiverServer` (TV) + `CastClient` (phone) on loopback (design §5.5, §6, plan §3.7). */
class CastProtocolTest {
    private class Sink : CastSink {
        val casts: MutableList<Pair<CastRequest, String>> = Collections.synchronizedList(mutableListOf())
        val controls: MutableList<ControlRequest> = Collections.synchronizedList(mutableListOf())
        @Volatile var accept = true
        @Volatile var snapshot = CastState()
        override fun onCast(req: CastRequest, fromIp: String): Boolean = accept.also { casts += req to fromIp }
        override fun onControl(c: ControlRequest): Boolean = accept.also { controls += c }
        override fun state(): CastState = snapshot
    }

    private val sink = Sink()
    private lateinit var server: CastReceiverServer
    private lateinit var client: CastClient
    private var port = 0
    private val http = FerryHttp.newClient(null, "vivo X200 Pro")

    private fun item(i: Int, name: String = "VID_$i.mp4") =
        CastItem("http://192.168.1.8:8767/c/0123/$i/$name", name, "video/mp4", 1000L + i)

    @Before
    fun setUp() {
        server = CastReceiverServer(sink, 0, log = { println("[tv] $it") })
        port = server.start()
        client = CastClient(http, HostPort("127.0.0.1", port))
    }

    @After
    fun tearDown() = server.close()

    private suspend fun expectHttp(code: Int, block: suspend () -> Unit) {
        try {
            block()
            fail("expected HTTP $code")
        } catch (e: FerryException.Http) {
            assertEquals(code, e.code)
        }
    }

    @Test
    fun castReachesTheSinkWithItsItems(): Unit = runBlocking {
        val req = CastRequest(CastRequest.VIDEO, "vivo X200 Pro", 5_000, 1, listOf(item(0), item(1, "Holiday café 🎉.mp4")))
        client.cast(req)
        val (got, from) = sink.casts.single()
        assertEquals(req, got)
        assertEquals("127.0.0.1", from)
        client.cast(CastRequest(CastRequest.PHOTOS, "vivo", 3_000, 0, listOf(CastItem("http://h:1/c/t/0/a.jpg", "a.jpg", "image/jpeg"))))
        assertEquals(CastRequest.PHOTOS, sink.casts[1].first.kind)
        assertEquals(3_000L, sink.casts[1].first.intervalMs)
    }

    @Test
    fun castWireFormatIsDesign6() { // the JSON the phone sends, field by field
        val body = """{"kind":"video","from":"vivo X200 Pro","interval_ms":5000,"start":0,
            "items":[{"url":"http://192.168.1.8:8767/c/abc/0/VID_1.mp4","name":"VID_1.mp4","mime":"video/mp4","size":123}]}"""
        val r = raw("POST", "/api/v1/cast", body)
        assertEquals(200, r.first)
        val c = sink.casts.single().first
        assertEquals(CastItem("http://192.168.1.8:8767/c/abc/0/VID_1.mp4", "VID_1.mp4", "video/mp4", 123), c.items.single())
        val sent = FerryJson.parseToJsonElement(FerryJson.encodeToString(CastRequest.serializer(), c)).jsonObject
        assertEquals(setOf("kind", "from", "interval_ms", "start", "items"), sent.keys)
    }

    @Test
    fun invalidCastsAreRefusedAndNeverReachTheSink(): Unit = runBlocking {
        expectHttp(400) { client.cast(CastRequest("music", "x", 5_000, 0, listOf(item(0)))) }
        expectHttp(400) { client.cast(CastRequest(CastRequest.VIDEO, "x", 5_000, 0, emptyList())) }
        expectHttp(400) { client.cast(CastRequest(CastRequest.VIDEO, "x", 5_000, 2, listOf(item(0)))) }
        expectHttp(400) { client.cast(CastRequest(CastRequest.VIDEO, "x", 5_000, 0, listOf(CastItem("file:///sdcard/x.mp4", "x.mp4", "video/mp4")))) }
        assertEquals(400, raw("POST", "/api/v1/cast", "not json").first)
        assertEquals(400, raw("POST", "/api/v1/cast", "").first)
        assertTrue(sink.casts.isEmpty())
        sink.accept = false
        expectHttp(409) { client.cast(CastRequest(CastRequest.VIDEO, "x", 5_000, 0, listOf(item(0)))) }
    }

    @Test
    fun controlActionsIncludingSeekAndInterval(): Unit = runBlocking {
        val all = listOf(
            ControlRequest(ControlRequest.TOGGLE), ControlRequest(ControlRequest.PLAY), ControlRequest(ControlRequest.PAUSE),
            ControlRequest(ControlRequest.NEXT), ControlRequest(ControlRequest.PREV),
            ControlRequest(ControlRequest.SEEK, deltaMs = -10_000), ControlRequest(ControlRequest.SEEK, deltaMs = 30_000),
            ControlRequest(ControlRequest.INTERVAL, intervalMs = 8_000), ControlRequest(ControlRequest.STOP),
        )
        for (c in all) client.control(c)
        assertEquals(all, sink.controls.toList())
        assertEquals(200, raw("POST", "/api/v1/control", """{"action":"seek","delta_ms":-5000}""").first)
        assertEquals(-5_000L, sink.controls.last().deltaMs)
        assertEquals(200, raw("POST", "/api/v1/control", """{"action":"interval","interval_ms":3000}""").first)
        assertEquals(3_000L, sink.controls.last().intervalMs)
    }

    @Test
    fun invalidControlsAreRefused(): Unit = runBlocking {
        expectHttp(400) { client.control(ControlRequest("rewind")) }
        expectHttp(400) { client.control(ControlRequest(ControlRequest.SEEK)) }
        expectHttp(400) { client.control(ControlRequest(ControlRequest.INTERVAL)) }
        expectHttp(400) { client.control(ControlRequest(ControlRequest.INTERVAL, intervalMs = 0)) }
        assertTrue(sink.controls.isEmpty())
        sink.accept = false
        expectHttp(409) { client.control(ControlRequest(ControlRequest.NEXT)) }
    }

    @Test
    fun stateJson(): Unit = runBlocking {
        assertEquals(CastState(), client.state())
        sink.snapshot = CastState("video", 2, 5, "VID_3.mp4", true, 61_000, 300_000, 0)
        assertEquals(sink.snapshot, client.state())
        val (code, text) = raw("GET", "/api/v1/state", null)
        assertEquals(200, code)
        val o = FerryJson.parseToJsonElement(text).jsonObject
        assertEquals(setOf("kind", "index", "count", "title", "playing", "position_ms", "duration_ms", "interval_ms"), o.keys)
        assertEquals("video", o["kind"]!!.jsonPrimitive.content)
        assertEquals("61000", o["position_ms"]!!.jsonPrimitive.content)
        assertEquals("idle", FerryJson.decodeFromString(CastState.serializer(), "{}").kind)
    }

    @Test
    fun bodiesOver64KbAreRefused(): Unit = runBlocking {
        val many = (0 until 1_000).map { item(it, "A_rather_long_video_file_name_number_$it.mp4") }
        val req = CastRequest(CastRequest.PHOTOS, "x", 5_000, 0, many)
        assertTrue(FerryJson.encodeToString(CastRequest.serializer(), req).length > 64 * 1024)
        expectHttp(413) { client.cast(req) }
        assertTrue(sink.casts.isEmpty())
    }

    @Test
    fun unknownPathsAndMethodsAre404(): Unit = runBlocking {
        assertEquals(404, raw("GET", "/api/v1/stateX", null).first)
        assertEquals(404, raw("POST", "/api/v1/castle", "{}").first)
        assertEquals(404, raw("GET", "/api/v1/cast", null).first)
    }

    @Test
    fun tvGoneIsUnreachable(): Unit = runBlocking {
        val free = ServerSocket(0).use { it.localPort }
        val gone = CastClient(http, HostPort("127.0.0.1", free))
        try {
            gone.state()
            fail("expected Unreachable")
        } catch (e: FerryException.Unreachable) {
            // ok
        }
        try {
            gone.localAddressTowardsTv()
            fail("expected Unreachable")
        } catch (e: FerryException.Unreachable) {
            // ok
        }
    }

    @Test
    fun localAddressTowardsTvIsTheConnectedSocketsAddress(): Unit = runBlocking {
        assertEquals("127.0.0.1", client.localAddressTowardsTv())
    }

    private fun raw(method: String, path: String, body: String?): Pair<Int, String> {
        val b = Request.Builder().url("http://127.0.0.1:$port$path")
        if (method == "POST") b.post((body ?: "").toRequestBody("application/json".toMediaType())) else b.get()
        http.newCall(b.build()).execute().use { return it.code to (it.body?.string() ?: "") }
    }
}
