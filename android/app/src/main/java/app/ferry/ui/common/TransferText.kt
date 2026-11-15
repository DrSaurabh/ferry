package app.ferry.ui.common

import android.content.res.Resources
import app.ferry.R
import app.ferry.core.upload.EngineState
import app.ferry.core.upload.FailReason
import app.ferry.core.upload.ItemRow
import app.ferry.core.upload.ItemStatus
import app.ferry.core.upload.Phase
import app.ferry.core.upload.StopReason
import app.ferry.core.upload.Summary

/** Numbers behind the transfer wording; pure Kotlin (TransferMathTest). */
object TransferMath {
    /** "Sending 5 of 20": the file being sent = finished files + 1, capped at the total. */
    fun position(items: List<ItemRow>): Pair<Int, Int> {
        val done = items.count {
            it.status !is ItemStatus.Waiting && it.status !is ItemStatus.Checking && it.status !is ItemStatus.Sending
        }
        return minOf(done + 1, items.size) to items.size
    }

    fun fraction(s: EngineState): Float =
        if (s.totalBytes <= 0) 0f else (s.sentBytes.toDouble() / s.totalBytes).toFloat().coerceIn(0f, 1f)

    fun percent(st: ItemStatus.Sending): Int =
        if (st.total <= 0) 0 else ((st.sent * 100) / st.total).toInt().coerceIn(0, 100)

    /** Failed + not sent: what Retry would re-queue. */
    fun retryable(s: Summary): Int = s.failed + s.notSent
}

/** All transfer wording (plan §4.12) for the Transfer screen, Home's card and the notifications. */
class TransferWords(private val r: Resources) {
    fun pcName(s: EngineState?): String = s?.pcName?.takeIf { it.isNotBlank() } ?: r.getString(R.string.your_pc)

    fun headline(s: EngineState): String = when (s.phase) {
        Phase.Idle -> r.getString(R.string.preparing)
        Phase.Checking -> r.getString(R.string.transfer_checking, pcName(s))
        Phase.Sending -> TransferMath.position(s.items).let { (n, total) -> r.getString(R.string.transfer_sending, n, total) }
        Phase.WaitingForPc -> r.getString(R.string.waiting_for_pc, pcName(s))
        Phase.Finished -> s.summary?.let { summary(it) } ?: ""
    }

    fun notificationTitle(s: EngineState): String =
        if (s.phase == Phase.Sending) {
            TransferMath.position(s.items).let { (n, total) -> r.getString(R.string.notif_sending, n, total, pcName(s)) }
        } else {
            headline(s)
        }

    /** "18.2 MB/s · about 1 min left" while sending, else "". */
    fun detail(s: EngineState): String {
        if (s.phase != Phase.Sending) return ""
        return listOfNotNull(Format.speed(s.speedBps.toDouble()).takeIf { it.isNotEmpty() }, Format.eta(s.etaSeconds))
            .joinToString(" · ")
    }

    fun summary(s: Summary): String = r.getString(R.string.summary, s.saved, s.alreadyOnPc)

    /** The lines under the summary: without location, failed (Retry), and why the queue stopped. */
    fun summaryExtras(s: Summary, pcName: String): List<String> = listOfNotNull(
        if (s.savedWithoutLocation > 0) r.getString(R.string.summary_no_location, s.savedWithoutLocation) else null,
        stopLine(s, pcName),
        if (TransferMath.retryable(s) > 0) r.getString(R.string.summary_failed, TransferMath.retryable(s)) else null,
    )

    private fun stopLine(s: Summary, pcName: String): String? = when (val stop = s.stop) {
        is StopReason.DiskFull -> stop.missingBytes?.takeIf { it > 0 }
            ?.let { r.getString(R.string.pc_disk_full_need, Format.size(it)) } ?: r.getString(R.string.pc_disk_full)
        StopReason.PcNotFound -> r.getString(R.string.gave_up_waiting, pcName, s.notSent)
        StopReason.OldPc -> r.getString(R.string.status_old_pc)
        StopReason.TimeLimit -> r.getString(R.string.time_limit)
        StopReason.Cancelled, null -> null
    }

    fun row(row: ItemRow): String = when (val st = row.status) {
        ItemStatus.Waiting -> r.getString(R.string.row_waiting)
        ItemStatus.Checking -> r.getString(R.string.row_checking)
        is ItemStatus.Sending -> r.getString(R.string.row_sending, TransferMath.percent(st))
        is ItemStatus.Saved -> when {
            !row.original -> r.getString(R.string.row_saved_no_location)
            st.renamed -> r.getString(R.string.row_saved_as, st.savedAs)
            else -> r.getString(R.string.row_saved)
        }
        is ItemStatus.AlreadyOnPc -> r.getString(R.string.row_already)
        is ItemStatus.Failed -> when (st.reason) {
            FailReason.ConnectionKeptDropping -> r.getString(R.string.row_failed_drops)
            FailReason.FileChanged -> r.getString(R.string.row_failed_changed)
            FailReason.CantRead -> r.getString(R.string.row_failed_read)
            FailReason.CantReadOriginal -> r.getString(R.string.row_failed_location)
            FailReason.PcError -> r.getString(R.string.status_pc_error, st.detail ?: "500")
            FailReason.BadRequest -> r.getString(R.string.status_pc_error, st.detail ?: "400")
        }
        ItemStatus.Cancelled -> r.getString(R.string.row_cancelled)
        is ItemStatus.NotSent -> when (st.reason) {
            StopReason.PcNotFound -> r.getString(R.string.row_not_sent)
            is StopReason.DiskFull -> r.getString(R.string.row_not_sent_disk)
            StopReason.TimeLimit -> r.getString(R.string.row_not_sent_time)
            StopReason.OldPc -> r.getString(R.string.row_not_sent_old)
            StopReason.Cancelled -> r.getString(R.string.row_cancelled)
        }
    }
}
