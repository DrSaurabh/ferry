package app.ferry.probe

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ferry.MainActivity
import app.ferry.core.HostPort
import app.ferry.core.SystemClock
import app.ferry.core.net.FerryClient
import app.ferry.core.net.FerryHttp
import app.ferry.core.upload.ItemStatus
import app.ferry.core.upload.OpenedSource
import app.ferry.core.upload.SourceMeta
import app.ferry.core.upload.UploadEngine
import app.ferry.core.upload.UploadItem
import app.ferry.core.upload.UploadSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ph0b: the emulator reaches the host's ferry.py at 10.0.2.2 with the real HTTP stack; one screenshot.
 * Ph2a: the request goes through `:core` (`FerryClient.info()`), the first real `:core` code on the
 * API 25 (Fire OS 6) level [A3].
 * Ph2b: a 1 MB in-memory send through `:core`'s `UploadEngine` (coroutines, workers, state flow).
 */
@RunWith(AndroidJUnit4::class)
class HostReachProbe {
    @Test
    fun infoAnswersThroughCoreFerryClient() {
        val client = FerryClient(FerryHttp.newClient(null, "CI probe ’📱"), HostPort.parse(Probe.pc)!!)
        val info = runBlocking { client.info() }
        Probe.record("host_reach", "app=${info.app} version=${info.version} protocol=${info.protocol} sdk=${Build.VERSION.SDK_INT}")
        assertEquals("ferry", info.app)
        assertEquals(2, info.protocol)
    }

    @Test
    fun uploadEngineSendsOneMegabyte() {
        val client = FerryClient(FerryHttp.newClient(null, "CI probe ’📱"), HostPort.parse(Probe.pc)!!)
        val bytes = ByteArray(1 shl 20).also { java.util.Random().nextBytes(it) }
        val size = bytes.size.toLong()
        val source = object : UploadSource {
            override val displayName = "probe"
            override fun open(): OpenedSource = object : OpenedSource {
                override val length = size
                override val stream: InputStream = ByteArrayInputStream(bytes)
                override fun close() = stream.close()
            }
            override fun refresh() = SourceMeta(size, null)
        }
        val name = "probe-engine-sdk${Build.VERSION.SDK_INT}-${System.currentTimeMillis()}.bin"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val engine = UploadEngine(scope, { client }, { null }, SystemClock)
            engine.add(listOf(UploadItem(1, source, name, size, System.currentTimeMillis())))
            val summary = runBlocking { withTimeout(60_000) { engine.awaitFinished() } }
            val status = engine.state.value.items.single().status
            Probe.record("upload_engine", "$status saved=${summary.saved} sdk=${Build.VERSION.SDK_INT}")
            assertEquals(1, summary.saved)
            assertEquals(ItemStatus.Saved(name, false), status)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun networkTransports() {
        val cm = Probe.context.getSystemService(ConnectivityManager::class.java)
        val names = mapOf(
            NetworkCapabilities.TRANSPORT_WIFI to "wifi",
            NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
            NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
            NetworkCapabilities.TRANSPORT_VPN to "vpn",
        )
        @Suppress("DEPRECATION")
        val all = cm.allNetworks.map { n ->
            val caps = cm.getNetworkCapabilities(n)
            val t = names.filter { caps?.hasTransport(it.key) == true }.values.joinToString("+")
            "$n:${t.ifEmpty { "other" }}"
        }
        val active = cm.activeNetwork
        val activeCaps = active?.let { cm.getNetworkCapabilities(it) }
        val activeT = names.filter { activeCaps?.hasTransport(it.key) == true }.values.joinToString("+")
        Probe.record("transports", "active=${activeT.ifEmpty { "none" }} all=$all")
        assertTrue("no active network", active != null)
    }

    @Test
    fun screenshot() {
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(2_000)
            val f = Shots.take("launch")
            Probe.record("screenshot", "${f.absolutePath} ${f.length()} bytes")
            assertTrue(f.length() > 1000)
        }
    }
}
