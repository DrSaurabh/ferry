package app.ferry.core.discovery

import app.ferry.core.HostPort
import app.ferry.core.model.PcInfo
import app.ferry.core.model.Peer
import app.ferry.core.net.FerryApi
import app.ferry.core.net.FerryException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** What the app remembers about the PC (design §3.5); `:app` backs it with SharedPreferences. */
interface PcStore {
    /** The PC that answered last time [B16]. */
    var lastKnown: HostPort?

    /** Settings → "PC address" (typed by the user). */
    var manual: HostPort?

    /** The PC the user picked in Settings when several answered; preferred among broadcast replies. */
    var chosenName: String?
}

sealed interface LocateResult {
    data class Found(val api: FerryApi, val info: PcInfo) : LocateResult
    data class OldPc(val base: HostPort, val protocol: Int) : LocateResult
    data class NotFound(val candidates: List<Peer>) : LocateResult
    object NoNetwork : LocateResult
}

/**
 * Finds the PC (design §3.5): manual address → last-known → broadcast → verify `/info` → remember.
 *
 * - Manual and last-known are each tried with [quickTimeoutMs] (1.5 s). A typed address that does not answer
 *   **falls through** to last-known and broadcast; when the broadcast then finds a PC, that PC becomes
 *   last-known and the dead typed address is cleared, so a PC whose address changed is found again by itself
 *   [PB22] [H35].
 * - Broadcast replies with `device_type "pc"` are verified in order (the [PcStore.chosenName] PC first); the
 *   first that answers `/info` is used and stored as last-known.
 * - [discovery] null means there is no Wi-Fi/Ethernet network to broadcast on: if neither stored address
 *   answers, the result is [LocateResult.NoNetwork].
 * - `/info` with protocol < 2 → [LocateResult.OldPc] [B15] (not stored).
 */
class PcLocator(
    private val clientFor: (HostPort) -> FerryApi,
    private val discovery: DiscoveryClient?,
    private val store: PcStore,
    private val log: (String) -> Unit = {},
    private val quickTimeoutMs: Long = 1_500,
    private val peerTimeoutMs: Long = 3_000,
) {
    suspend fun locate(): LocateResult {
        val manual = store.manual
        var manualDead = false
        if (manual != null) {
            when (val r = verify(manual, quickTimeoutMs)) {
                is LocateResult.Found -> return found(manual, r)
                is LocateResult.OldPc -> return r
                else -> {
                    manualDead = true
                    log("locate: typed address $manual does not answer")
                }
            }
        }
        val last = store.lastKnown
        if (last != null && last != manual) {
            when (val r = verify(last, quickTimeoutMs)) {
                is LocateResult.Found -> return found(last, r)
                is LocateResult.OldPc -> return r
                else -> log("locate: last-known $last does not answer")
            }
        }
        if (discovery == null) return LocateResult.NoNetwork
        val pcs = broadcast("pc")
        val chosen = store.chosenName
        val ordered = if (chosen == null) pcs else pcs.sortedBy { if (it.name == chosen) 0 else 1 } // stable
        var old: LocateResult.OldPc? = null
        for (p in ordered) {
            val hp = HostPort(p.host, p.port)
            when (val r = verify(hp, peerTimeoutMs)) {
                is LocateResult.Found -> {
                    if (manualDead) {
                        store.manual = null
                        log("locate: found by broadcast at $hp; forgot the typed address $manual")
                    }
                    return found(hp, r)
                }
                is LocateResult.OldPc -> if (old == null) old = r
                else -> log("locate: ${p.name} at $hp answered the broadcast but not /info")
            }
        }
        return old ?: LocateResult.NotFound(pcs)
    }

    /** One `/info` call: Found, OldPc or NotFound(empty). Stores nothing. */
    suspend fun verify(hp: HostPort, timeoutMs: Long = quickTimeoutMs): LocateResult {
        val api = clientFor(hp)
        return try {
            LocateResult.Found(api, api.info(timeoutMs))
        } catch (e: FerryException.OldPc) {
            LocateResult.OldPc(hp, e.protocol)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) { // every FerryException is one
            LocateResult.NotFound(emptyList())
        }
    }

    /** TVs that answer the same broadcast (`device_type "tv"`, port 8766) [P9]. Empty without a network. */
    suspend fun findTvs(): List<Peer> = if (discovery == null) emptyList() else broadcast("tv")

    private fun found(hp: HostPort, r: LocateResult.Found): LocateResult.Found {
        if (store.lastKnown != hp) store.lastKnown = hp
        return r
    }

    private suspend fun broadcast(type: String): List<Peer> {
        val d = discovery ?: return emptyList()
        val peers = try {
            d.discover()
        } catch (e: IOException) {
            log("locate: broadcast failed: $e")
            return emptyList()
        }
        return peers.filter { it.deviceType == type }
    }
}
