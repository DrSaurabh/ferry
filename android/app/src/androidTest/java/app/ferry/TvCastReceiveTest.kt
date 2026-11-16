package app.ferry

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ferry.core.HostPort
import app.ferry.core.cast.CastClient
import app.ferry.core.discovery.DatagramTransport
import app.ferry.core.discovery.DiscoveryClient
import app.ferry.core.model.CastItem
import app.ferry.core.model.CastRequest
import app.ferry.core.model.CastState
import app.ferry.core.model.ControlRequest
import app.ferry.net.ConnectionState
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import app.ferry.ui.nav.Screen
import app.ferry.ui.tv.CastReceiverHost
import app.ferry.ui.tv.CastSinkImpl
import app.ferry.ui.tv.KeepScreenOn
import app.ferry.ui.tv.TvPlayerState
import app.ferry.ui.tv.TvViewerState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress

/**
 * TV suite (Ph9, B80) [T5] [A10]: the receiving side on the TV image, in TV mode. The test plays the phone: it finds
 * the TV by discovery (UDP 8765 → `device_type "tv"`, port 8766), casts the PC's `big.mp4` and three photos by URL
 * (the TV only fetches URLs; the phone's file server is proven by the phone suite's CastLoopbackTest), and checks:
 * the cast shows on top of Home and keeps the screen on; OK on the remote pauses it, the phone's `play` resumes it;
 * Back ends it (Home, focus back, state idle); a slideshow advances and takes Next / interval; a phone that stops
 * answering → "Lost connection to the phone" and Home within ~12 s.
 */
@RunWith(AndroidJUnit4::class)
class TvCastReceiveTest {
    private val tvAddr = HostPort("127.0.0.1", CastReceiverHost.PORT)

    @Test
    fun receivesCastsShowsThemAndHandsBackToHome() {
        LaunchHelpers.cleanPrefs(welcomeDone = false)
        AppGraph.prefs.slideshowSeconds = 5
        TvHelpers.leaveTouchMode()
        LaunchHelpers.launch().use { sc ->
            SendHelpers.waitConnected()
            val t = TvHelpers(sc)
            t.waitOutbox("big.mp4", "photo1.jpg", "photo2.jpg", "photo3.jpg")
            t.waitFocus("a card focused", 20_000) { t.current != null }
            val homeFocus = t.current
            SendHelpers.waitUntil("cast receiver listening", 10_000) { CastReceiverHost.listening }

            // ---- Discovery as a phone does it (unicast to the TV's own address here; broadcasts don't leave the emulator, B27).
            val own = AppGraph.network.ownIpv4 ?: "127.0.0.1"
            val peers = runBlocking {
                DiscoveryClient({ DatagramTransport() }, { listOf(InetAddress.getByName(own)) }, "CI phone" to "phone").discover()
            }
            Probe.record("tv_discovery", "to $own: $peers")
            assertTrue("the TV answers discovery as a TV on 8766: $peers", peers.any { it.deviceType == "tv" && it.port == CastReceiverHost.PORT })

            val api = (AppGraph.connection.state.value as ConnectionState.Connected).api
            val files = t.vm.outbox.value!!.files
            fun item(name: String, mime: String): CastItem {
                val f = files.first { it.name == name }
                return CastItem(api.absoluteUrl(f.url), f.name, mime, f.size)
            }
            val client = CastClient(AppGraph.httpClient(), tvAddr)
            fun state(): CastState = runBlocking { client.state() }

            // ---- A video cast shows on top of Home and plays; the screen stays on.
            runBlocking { client.cast(CastRequest(CastRequest.VIDEO, "CI phone", 5_000, 0, listOf(item("big.mp4", "video/mp4")))) }
            SendHelpers.waitUntil("cast player on screen", 10_000) { CastSinkImpl.show.value != null && TvPlayerState.host != null }
            SendHelpers.waitUntil("the TV reports the video playing", 15_000) { state().let { it.kind == "video" && it.playing && it.positionMs > 1_000 } }
            assertTrue("Home stays under the cast (back stack unchanged)", t.screen() is Screen.TvHome)
            var keptOn = false
            sc.onActivity { a -> keptOn = KeepScreenOn.isOn(a) }
            assertTrue("screen kept on during a cast", keptOn)
            SendHelpers.settle()
            Shots.take("tv-cast-video")

            // ---- The TV's remote: OK pauses [T5]; the phone's "play" resumes.
            t.key(KeyEvent.KEYCODE_DPAD_CENTER)
            SendHelpers.waitUntil("OK on the remote pauses", 5_000) { !state().playing }
            runBlocking { client.control(ControlRequest(ControlRequest.PLAY)) }
            SendHelpers.waitUntil("phone's play resumes", 5_000) { state().playing }
            val p0 = state().positionMs
            runBlocking { client.control(ControlRequest(ControlRequest.SEEK, deltaMs = 30_000)) }
            SendHelpers.waitUntil("phone's seek +30 s", 5_000) { state().positionMs >= p0 + 25_000 }
            Probe.record("tv_cast_video", state())

            // ---- Back on the TV ends the cast: Home with its focus, state idle.
            SendHelpers.device.pressBack()
            SendHelpers.waitUntil("cast ended by Back", 10_000) { CastSinkImpl.show.value == null }
            assertEquals(CastState.IDLE, state().kind)
            t.waitFocus("focus back on Home's card", 10_000) { t.current == homeFocus }

            // ---- A slideshow cast advances, takes Next and the interval; stop from the phone.
            val photos = listOf("photo1.jpg", "photo2.jpg", "photo3.jpg").map { item(it, "image/jpeg") }
            runBlocking { client.cast(CastRequest(CastRequest.PHOTOS, "CI phone", 3_000, 0, photos)) }
            SendHelpers.waitUntil("slideshow on screen", 10_000) { TvViewerState.open && state().kind == "photos" }
            assertEquals(3_000L, state().intervalMs)
            SendHelpers.waitUntil("advances by itself", 8_000) { state().index == 1 }
            SendHelpers.settle()
            Shots.take("tv-cast-slideshow")
            runBlocking { client.control(ControlRequest(ControlRequest.NEXT)) }
            SendHelpers.waitUntil("phone's Next", 3_000) { state().index == 2 }
            runBlocking { client.control(ControlRequest(ControlRequest.INTERVAL, intervalMs = 10_000)) }
            SendHelpers.waitUntil("interval 10 s", 3_000) { state().intervalMs == 10_000L }
            runBlocking { client.control(ControlRequest(ControlRequest.STOP)) }
            SendHelpers.waitUntil("phone's stop → Home", 5_000) { CastSinkImpl.show.value == null && state().kind == CastState.IDLE }

            // ---- The phone goes away: nothing answers at the cast's URL → "Lost connection to the phone", Home.
            val gone = CastItem("http://127.0.0.1:9/c/none/0/gone.jpg", "gone.jpg", "image/jpeg", 1)
            runBlocking { client.cast(CastRequest(CastRequest.PHOTOS, "CI phone", 5_000, 0, listOf(gone))) }
            SendHelpers.waitUntil("cast shown", 5_000) { CastSinkImpl.show.value != null }
            val shownAt = System.currentTimeMillis()
            SendHelpers.waitUntil("lost phone → Home", 20_000) { CastSinkImpl.show.value == null }
            val after = System.currentTimeMillis() - shownAt
            Probe.record("tv_lost_phone_after_ms", after)
            assertTrue("gave up after about 10 s ($after ms)", after in 9_000..16_000)
            assertTrue(SendHelpers.logged("has not answered for 10 s"))
            assertEquals(CastState.IDLE, state().kind)
            Probe.record("tv_cast_received", CastSinkImpl.received.toList())
        }
    }
}
