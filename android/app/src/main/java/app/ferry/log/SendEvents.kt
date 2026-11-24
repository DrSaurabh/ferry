package app.ferry.log

import app.ferry.core.log.EventLog
import app.ferry.core.upload.EngineState
import app.ferry.core.upload.ItemStatus
import app.ferry.core.upload.Phase
import app.ferry.core.upload.StopReason
import java.security.SecureRandom
import java.util.Locale

/**
 * The 2.0 send re-expressed as 2.1 events (L1, catalogue part 1): `send.start` when files are added,
 * `send.item` once per finished file, `send.wait` on entering "waiting for the PC", `send.done` / `send.cancel`
 * when the run finishes. Fed with every [EngineState] the queue publishes (`Transfers`), one instance per engine
 * (= one batch id, 4 hex). [onEnded] runs after `send.done`/`send.cancel` (LogShipper "after a send ends").
 */
class SendEvents(
    private val log: EventLog,
    private val nowMs: () -> Long,
    private val onEnded: () -> Unit,
    val batch: String = String.format(Locale.US, "%04x", SecureRandom().nextInt(0x10000)),
) {
    // Only the queue's state collector calls onState (one coroutine); added() comes from the service. No monitor
    // is held while writing to the log (Ph11b CI: keep the state collector free of any lock but EventLog's).
    private val logged = java.util.Collections.synchronizedSet(HashSet<Long>())
    @Volatile private var lastPhase: Phase? = null
    @Volatile private var startMs = 0L
    private val index = java.util.concurrent.atomic.AtomicInteger()

    fun added(files: Int, bytes: Long, pc: String?) {
        if (startMs == 0L || lastPhase == Phase.Finished) startMs = nowMs()
        log.event("send.start", "batch" to batch, "files" to files, "bytes" to bytes, "pc" to (pc ?: "?"))
    }

    fun onState(s: EngineState) {
        for (r in s.items) {
            val result = when (val st = r.status) {
                is ItemStatus.Saved -> if (st.renamed) "saved_renamed" else "saved"
                is ItemStatus.AlreadyOnPc -> "already_on_pc"
                is ItemStatus.Failed -> "failed_" + st.reason.name.lowercase(Locale.US)
                ItemStatus.Cancelled -> "cancelled"
                is ItemStatus.NotSent -> "not_sent_" + reasonName(st.reason)
                else -> {
                    logged.remove(r.id) // Retry / Send anyway: the file goes again
                    null
                }
            } ?: continue
            if (!logged.add(r.id)) continue
            log.event("send.item", "batch" to batch, "i" to index.incrementAndGet(), "name" to r.name, "size" to r.size, "result" to result)
        }
        val was = lastPhase
        lastPhase = s.phase
        if (s.phase == was) return
        when (s.phase) {
            Phase.WaitingForPc -> log.event("send.wait", "batch" to batch, "reason" to "pc_not_found")
            Phase.Finished -> {
                val sum = s.summary
                val ms = nowMs() - startMs
                if (sum?.stop == StopReason.Cancelled) {
                    log.event("send.cancel", "batch" to batch, "left" to (sum.cancelled + sum.notSent))
                } else {
                    log.event(
                        "send.done", "batch" to batch, "sent" to (sum?.saved ?: 0), "skipped" to (sum?.alreadyOnPc ?: 0),
                        "failed" to ((sum?.failed ?: 0) + (sum?.notSent ?: 0)), "ms" to ms,
                    )
                }
                onEnded()
            }
            else -> Unit
        }
    }

    private fun reasonName(r: StopReason): String = when (r) {
        is StopReason.DiskFull -> "disk_full"
        StopReason.PcNotFound -> "pc_not_found"
        StopReason.TimeLimit -> "time_limit"
        StopReason.OldPc -> "old_pc"
        StopReason.Cancelled -> "cancelled"
    }
}
