package app.ferry.probe

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ferry.MainActivity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Ph0b: the emulator reaches the host's ferry.py at 10.0.2.2 with the real HTTP stack; one screenshot. */
@RunWith(AndroidJUnit4::class)
class HostReachProbe {
    @Test
    fun infoAnswersThroughOkHttp() {
        val info = JSONObject(Probe.get("/api/v1/info"))
        Probe.record("host_reach", "app=${info.optString("app")} version=${info.optString("version")} sdk=${Build.VERSION.SDK_INT}")
        assertEquals("ferry", info.getString("app"))
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
