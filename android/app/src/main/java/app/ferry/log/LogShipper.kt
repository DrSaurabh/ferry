package app.ferry.log

import app.ferry.core.Clock
import app.ferry.core.log.EventLog
import app.ferry.core.log.LogReader
import app.ferry.core.net.FerryApi
import app.ferry.core.net.FerryException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends the event log to the PC (design §7.2, plan §2.3, CP137 [PO6]; the same rule on phone and TV):
 * - [onBackground]: the app went to the background (5 s best effort);
 * - [afterSend]: a send ended;
 * - [afterPcCall]: a PC call succeeded (info/outbox/check) and ≥ 10 min passed since the last ship, or none yet
 *   in this process.
 * One ship in flight; a trigger during it runs one more round afterwards (so a `send.done` written meanwhile is not
 * left behind). Each round posts ≤ 512 KB chunks from the ship cursor until nothing is left; the cursor moves only
 * to the PC's `last_seq`. A 404 (2.0 PC) turns shipping off for this process (M8). Failures wait for the next trigger.
 */
class LogShipper(
    private val scope: CoroutineScope,
    private val events: EventLog,
    private val reader: LogReader,
    private val api: () -> FerryApi?,
    private val clock: Clock,
    /** `clock peer=pc offset_ms=` (M18) — the caller logs it when it changed. */
    private val onPcTime: (Long) -> Unit,
    private val diag: (String) -> Unit,
    private val everyMs: Long = 10 * 60_000L,
) {
    @Volatile var off = false
        private set
    @Volatile private var lastShipMs = 0L
    private val running = AtomicBoolean(false)
    private val again = AtomicBoolean(false)

    fun onBackground() = ship("bg", 5_000)

    fun afterSend() = ship("send", 30_000)

    fun afterPcCall() {
        val last = lastShipMs
        if (last == 0L || clock.nowMs() - last >= everyMs) ship("pc", 30_000)
    }

    private fun ship(why: String, timeoutMs: Long) {
        if (off) return
        if (!running.compareAndSet(false, true)) {
            again.set(true)
            return
        }
        scope.launch {
            try {
                do {
                    again.set(false)
                    withTimeoutOrNull(timeoutMs) { round(why) }
                } while (again.get() && !off)
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun round(why: String) {
        val a = api() ?: return
        lastShipMs = clock.nowMs()
        var chunks = 0
        while (chunks < 20) { // ≤ 10 MB per round; the log holds ≤ 5 MB
            val chunk = reader.linesAfter(events.shipped)
            if (chunk.isEmpty || chunk.lastSeq == null) break
            val reply = try {
                a.postLogs(chunk.fromSeq, chunk.body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FerryException.LogsUnsupported) {
                off = true
                diag("PC does not take logs (older Ferry): shipping off")
                return
            } catch (e: Exception) {
                diag("ship ($why) failed: $e")
                return
            }
            events.markShipped(reply.lastSeq)
            reply.timeMs?.let(onPcTime)
            chunks++
            // The PC kept less than we sent (should not happen): stop rather than loop on the same lines.
            if (reply.lastSeq < chunk.lastSeq) break
        }
    }
}
