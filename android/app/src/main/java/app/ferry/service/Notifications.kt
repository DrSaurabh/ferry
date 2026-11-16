package app.ferry.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.ferry.AppGraph
import app.ferry.FerryApp
import app.ferry.MainActivity
import app.ferry.R
import app.ferry.core.download.DownloadStatus
import app.ferry.core.upload.EngineState
import app.ferry.net.ConnectionState
import app.ferry.core.upload.Phase
import app.ferry.core.upload.Summary
import app.ferry.ui.common.TransferMath
import app.ferry.ui.common.TransferWords

/**
 * Notifications of the transfer (design §3.4, plan §4.5) [A6]: the ongoing progress notification of the
 * foreground service (updated ≤ 1/s by the service) and the separate **Results** notification posted after the
 * service has stopped. Tapping either opens the Transfer screen. If notifications are not allowed, nothing breaks.
 */
object Notifications {
    const val ID_PROGRESS = 101
    const val ID_RESULTS = 102

    fun progress(ctx: Context, s: EngineState?): Notification {
        val w = TransferWords(ctx.resources)
        val b = NotificationCompat.Builder(ctx, FerryApp.CH_TRANSFERS)
            .setSmallIcon(R.drawable.ic_stat_ferry)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openTransfer(ctx))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, ctx.getString(R.string.cancel), serviceAction(ctx, TransferService.ACTION_CANCEL, 1))
        if (s == null) {
            b.setContentTitle(ctx.getString(R.string.preparing)).setProgress(0, 0, true)
        } else {
            b.setContentTitle(w.notificationTitle(s))
            w.detail(s).takeIf { it.isNotEmpty() }?.let { b.setContentText(it) }
            when (s.phase) {
                Phase.Sending -> b.setProgress(1000, (TransferMath.fraction(s) * 1000).toInt(), false)
                Phase.Idle, Phase.Checking -> b.setProgress(0, 0, true)
                else -> {}
            }
        }
        return b.build()
    }

    /** "18 saved · 2 already on PC" (+ "2 failed. Tap to retry" with a Retry action) [A22]. */
    fun postResults(ctx: Context, s: Summary, pcName: String) {
        val w = TransferWords(ctx.resources)
        val extras = w.summaryExtras(s, pcName)
        val b = NotificationCompat.Builder(ctx, FerryApp.CH_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_ferry)
            .setContentTitle(w.summary(s))
            .setAutoCancel(true)
            .setContentIntent(openTransfer(ctx))
        if (extras.isNotEmpty()) {
            b.setContentText(extras.last()).setStyle(NotificationCompat.BigTextStyle().bigText(extras.joinToString("\n")))
        }
        if (TransferMath.retryable(s) > 0) {
            b.addAction(0, ctx.getString(R.string.retry), serviceAction(ctx, TransferService.ACTION_RETRY, 2))
        }
        try {
            NotificationManagerCompat.from(ctx).notify(ID_RESULTS, b.build())
        } catch (e: SecurityException) { // notifications not allowed: the summary is on the Transfer screen
            AppGraph.log("transfer", "results notification not shown: $e")
        }
    }

    fun update(ctx: Context, s: EngineState?) = notifyProgress(ctx, progress(ctx, s))

    fun notifyProgress(ctx: Context, n: Notification) {
        try {
            NotificationManagerCompat.from(ctx).notify(ID_PROGRESS, n)
        } catch (e: SecurityException) {
        }
    }

    /**
     * The same ongoing notification while only outbox downloads run (Ph7): "Downloading report.pdf" + "45 % · 2 more
     * waiting", or "Waiting for <PC>…"; Cancel stops all downloads; a tap opens From PC.
     */
    fun downloadProgress(ctx: Context, st: Map<Int, DownloadStatus>, waiting: Boolean): Notification {
        val b = NotificationCompat.Builder(ctx, FerryApp.CH_TRANSFERS)
            .setSmallIcon(R.drawable.ic_stat_ferry)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openScreen(ctx, MainActivity.OPEN_FROM_PC, 3))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, ctx.getString(R.string.cancel), serviceAction(ctx, TransferService.ACTION_CANCEL_DOWNLOADS, 4))
        val running = st.entries.firstOrNull { it.value is DownloadStatus.Running }
        val queued = st.values.count { it is DownloadStatus.Queued }
        val pc = (AppGraph.connection.state.value as? ConnectionState.Connected)?.name ?: ctx.getString(R.string.your_pc)
        if (waiting) {
            b.setContentTitle(ctx.getString(R.string.waiting_for_pc, pc)).setProgress(0, 0, true)
        } else if (running != null) {
            val r = running.value as DownloadStatus.Running
            val name = AppGraph.downloads.file(running.key)?.name ?: ""
            val pct = if (r.total > 0) (r.got * 100 / r.total).toInt() else 0
            b.setContentTitle(ctx.getString(R.string.notif_downloading, name))
                .setContentText(ctx.getString(R.string.notif_download_detail, pct, queued))
                .setProgress(100, pct, false)
        } else {
            b.setContentTitle(ctx.getString(R.string.notif_downloading, "")).setProgress(0, 0, true)
        }
        return b.build()
    }

    private fun openTransfer(ctx: Context): PendingIntent = openScreen(ctx, MainActivity.OPEN_TRANSFER, 0)

    private fun openScreen(ctx: Context, open: String, code: Int): PendingIntent = PendingIntent.getActivity(
        ctx, code,
        Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, open)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Notification actions start the service directly [A6]: getForegroundService on 26+, getService on 25. */
    private fun serviceAction(ctx: Context, action: String, code: Int): PendingIntent {
        val i = Intent(ctx, TransferService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(ctx, code, i, flags)
        else PendingIntent.getService(ctx, code, i, flags)
    }
}
