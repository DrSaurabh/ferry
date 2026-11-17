package app.ferry.ui.tv

import android.app.Activity
import android.content.Context
import android.net.wifi.WifiManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.ferry.AppGraph
import app.ferry.BuildConfig
import app.ferry.core.cast.CastReceiverServer
import app.ferry.core.discovery.DatagramTransport
import app.ferry.core.discovery.DiscoveryCodec
import app.ferry.core.discovery.DiscoveryResponder
import app.ferry.core.model.DiscoveryReply
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * Receiving casts (design §5.5, plan §4.4) [T5]: not a service. While `MainActivity` is started (`onStart` →
 * `onStop`) [A10] [H14] the TV holds a `MulticastLock`, runs the [CastReceiverServer] on TCP 8766 over [CastSinkImpl]
 * and answers discovery on UDP 8765 as `device_type "tv"`. If UDP 8765 can't be bound (another app has it), that is
 * logged and the receiver still runs: the phone then finds the TV through its remembered address [A24].
 * Start and stop run in order on one background thread (sockets are not opened on the main thread).
 *
 * Installed in TV mode, and in phone mode only by the debug extra `app.ferry.debug.CAST_RECEIVER` [B27] (the
 * emulator loopback test).
 */
class CastReceiverHost(activity: Activity) : DefaultLifecycleObserver {
    private val ref = WeakReference(activity)
    private val app = activity.applicationContext
    private var server: CastReceiverServer? = null
    private var responder: DiscoveryResponder? = null
    private var lock: WifiManager.MulticastLock? = null
    private var port = PORT
    private var running = false // worker thread only
    private var networkWatch: Job? = null

    override fun onStart(owner: LifecycleOwner) {
        CastSinkImpl.activity = ref
        worker.execute { startAll() }
        // Audit O9: the discovery socket is bound to the Wi-Fi network it was opened on. After the TV's Wi-Fi
        // reconnects (router restart) that binding is dead, so the responder is opened again on the new network;
        // otherwise the phone would not find this TV until Ferry is reopened.
        networkWatch = AppGraph.scope.launch {
            AppGraph.network.chosen.map { it?.network }.distinctUntilChanged().drop(1).collect {
                worker.execute { restartResponder() }
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        networkWatch?.cancel()
        networkWatch = null
        CastSinkImpl.stop("Ferry left the screen") // the phone sees "idle" and ends its session
        worker.execute { stopAll() }
    }

    private fun startAll() {
        lock = try {
            (app.getSystemService(Context.WIFI_SERVICE) as WifiManager).createMulticastLock("Ferry:cast").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            log("multicast lock not taken: $e")
            null
        }
        port = PORT
        server = try {
            CastReceiverServer(CastSinkImpl, PORT, { log(it) }).also { port = it.start() }
        } catch (e: Exception) {
            log("can't listen for casts on TCP $PORT: $e")
            null
        }
        responder = startResponder()
        running = true
        listening = server != null
        log("receiver on: tcp ${if (server != null) port else "-"}, udp ${if (responder != null) DiscoveryCodec.PORT else "-"}")
    }

    private fun startResponder(): DiscoveryResponder? = try {
        val answerPort = port
        DiscoveryResponder(
            DatagramTransport(DiscoveryCodec.PORT, binder = AppGraph.network::bindDatagram),
            identity = { DiscoveryReply(name = AppGraph.deviceName, deviceType = "tv", port = answerPort, version = BuildConfig.VERSION_NAME) },
            log = { log(it) },
        ).also { it.start() }
    } catch (e: Exception) { // UDP 8765 taken: the phone falls back to the remembered TV or a typed address
        log("discovery responder not started (UDP ${DiscoveryCodec.PORT}): $e")
        null
    }

    /** The network changed while the receiver runs: the discovery socket again, on the new network (O9). */
    private fun restartResponder() {
        if (!running) return
        responder?.close()
        responder = startResponder()
        restarts++
        log("network changed: discovery responder ${if (responder != null) "restarted" else "not restarted"}")
    }

    private fun stopAll() {
        responder?.close()
        responder = null
        server?.close()
        server = null
        try {
            lock?.release()
        } catch (e: Exception) {
        }
        lock = null
        running = false
        listening = false
        log("receiver off")
    }

    private fun log(msg: String) = AppGraph.log("cast", msg)

    companion object {
        const val PORT = 8766
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ferry-cast-receiver").apply { isDaemon = true } }

        /** True while the TCP receiver is up (tests). */
        @Volatile var listening = false
            private set

        /** Responder restarts after a network change (log / tests). */
        @Volatile var restarts = 0
            private set
    }
}
