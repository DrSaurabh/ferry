package app.ferry.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * WakeLock + WifiLock while a transfer runs (design §3.4, plan §4.5) [A7]: the wake lock is taken with a 4 h timeout
 * and re-taken every 3.5 h while held; the Wi-Fi lock uses LOW_LATENCY on 29+, HIGH_PERF below (not relied on with
 * the screen off). [release] is idempotent. A lock the system refuses is skipped (the FGS still keeps the upload).
 */
class TransferLocks(ctx: Context, tag: String) {
    private val wake: PowerManager.WakeLock? = try {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ferry:$tag").apply { setReferenceCounted(false) }
    } catch (e: Exception) {
        null
    }

    private val wifi: WifiManager.WifiLock? = try {
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(mode, "Ferry:$tag").apply { setReferenceCounted(false) }
    } catch (e: Exception) {
        null
    }

    private var renew: Job? = null

    @Synchronized
    fun acquire(scope: CoroutineScope) {
        if (renew?.isActive == true) return
        hold()
        renew = scope.launch {
            while (true) {
                delay(RENEW_MS)
                synchronized(this@TransferLocks) { hold() }
            }
        }
    }

    @Synchronized
    fun release() {
        renew?.cancel()
        renew = null
        try { if (wake?.isHeld == true) wake.release() } catch (e: Exception) { }
        try { if (wifi?.isHeld == true) wifi.release() } catch (e: Exception) { }
    }

    private fun hold() {
        try { wake?.acquire(HOLD_MS) } catch (e: Exception) { }
        try { if (wifi != null && !wifi.isHeld) wifi.acquire() } catch (e: Exception) { }
    }

    private companion object {
        const val HOLD_MS = 4 * 60 * 60_000L
        const val RENEW_MS = 210 * 60_000L
    }
}
