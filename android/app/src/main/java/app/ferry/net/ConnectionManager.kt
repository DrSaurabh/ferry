package app.ferry.net

import app.ferry.core.HostPort
import app.ferry.core.discovery.DatagramTransport
import app.ferry.core.discovery.DiscoveryClient
import app.ferry.core.discovery.LocateResult
import app.ferry.core.discovery.PcLocator
import app.ferry.core.discovery.PcStore
import app.ferry.core.model.AppInfo
import app.ferry.core.model.PcInfo
import app.ferry.core.model.Peer
import app.ferry.core.net.FerryApi
import app.ferry.core.net.FerryClient
import app.ferry.core.update.AppUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** What Home / the TV header show (plan §4.3, §4.12). */
sealed interface ConnectionState {
    object Looking : ConnectionState
    data class Connected(val api: FerryApi, val info: PcInfo) : ConnectionState {
        val name: String get() = info.name.ifBlank { api.base.toString() }
    }
    data class NotFound(val candidates: List<Peer>) : ConnectionState
    object NoWifi : ConnectionState
    data class OldPc(val base: HostPort) : ConnectionState
}

/** The update banner's source (plan §4.8, design §4.6): set after each successful locate. */
sealed interface UpdateState {
    object None : UpdateState
    data class Available(val info: AppInfo) : UpdateState
}

/**
 * Wraps `:core`'s [PcLocator] (design §3.5, plan §1.3): locates on activity start, on network change and when a
 * request failed ([reportFailure]); publishes [state]; after each successful locate asks the PC for its app
 * bundle and sets [update] [C3]. One locate at a time: callers that queued behind a locate get its result.
 */
class ConnectionManager(
    private val scope: CoroutineScope,
    private val network: NetworkBinder,
    private val store: PcStore,
    private val self: () -> Pair<String, String>,
    private val installedVersionCode: Long,
    private val update: MutableStateFlow<UpdateState>,
    private val log: (String) -> Unit,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Looking)
    val state: StateFlow<ConnectionState> = _state
    private val lock = Mutex()
    @Volatile private var generation = 0

    init {
        scope.launch {
            network.chosen.map { it?.network }.distinctUntilChanged().drop(1).collect {
                log("network changed → locate again")
                relocate()
            }
        }
    }

    /** The PC in use, or null. */
    fun api(): FerryApi? = (_state.value as? ConnectionState.Connected)?.api

    /** Activity start (`onStart`) and "Try again". */
    fun refresh() {
        scope.launch { relocate() }
    }

    fun start() = refresh()

    /** A request to the PC failed with a network error: find it again in the background. */
    fun reportFailure() = refresh()

    /** Locates now (or waits for the locate in progress) and returns the PC's API, or null. */
    suspend fun relocate(): FerryApi? {
        val seen = generation
        return lock.withLock {
            if (generation != seen) return@withLock api() // a locate finished while we waited
            if (_state.value !is ConnectionState.Connected) _state.value = ConnectionState.Looking
            val r = try {
                locator().locate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("locate failed: $e")
                LocateResult.NotFound(emptyList())
            }
            val s = when (r) {
                is LocateResult.Found -> ConnectionState.Connected(r.api, r.info)
                is LocateResult.OldPc -> ConnectionState.OldPc(r.base)
                is LocateResult.NotFound -> ConnectionState.NotFound(r.candidates)
                LocateResult.NoNetwork -> ConnectionState.NoWifi
            }
            log("locate: " + when (s) {
                is ConnectionState.Connected -> "connected to ${s.name} at ${s.api.base} (Ferry ${s.info.version})"
                is ConnectionState.NotFound -> "not found (${s.candidates.size} answered the broadcast)"
                is ConnectionState.OldPc -> "older Ferry at ${s.base}"
                else -> s.javaClass.simpleName
            })
            _state.value = s
            generation++
            if (r is LocateResult.Found) checkUpdate(r.api)
            (s as? ConnectionState.Connected)?.api
        }
    }

    /** Every PC that answers a broadcast now (Settings' list; PcLocator returns only the one it uses). */
    suspend fun discoverPcs(): List<Peer> {
        val d = discoveryClient() ?: return emptyList()
        return try {
            d.discover().filter { it.deviceType == "pc" }
        } catch (e: IOException) {
            log("discover failed: $e")
            emptyList()
        }
    }

    private fun checkUpdate(api: FerryApi) {
        scope.launch {
            val info = try {
                api.appInfo()
            } catch (e: IOException) {
                log("app info failed: $e")
                return@launch
            }
            val newer = AppUpdate.isNewer(info, installedVersionCode)
            update.value = if (newer && info != null) UpdateState.Available(info) else UpdateState.None
            if (newer) log("update available: ${info?.versionName} (${info?.versionCode} > $installedVersionCode)")
        }
    }

    private fun discoveryClient(): DiscoveryClient? {
        if (!network.hasNetwork) return null
        return DiscoveryClient(
            transport = { DatagramTransport(binder = network::bindDatagram) },
            targets = { network.broadcastTargets() },
            self = self(),
            log = log,
        )
    }

    private fun locator() = PcLocator(
        clientFor = { hp -> FerryClient(network.httpClient(), hp) },
        discovery = discoveryClient(),
        store = store,
        log = log,
    )
}
