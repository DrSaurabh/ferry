package app.ferry.media

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import app.ferry.AppGraph
import app.ferry.service.TransferService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A photo/video chosen in Ferry's picker (MediaStore `_ID`). */
data class MediaPick(val id: Long, val isVideo: Boolean)

sealed interface SendRequest {
    data class Picks(val picks: List<MediaPick>) : SendRequest
    /** Share → Ferry or "Send other files" (SAF); [source] is "share" or "documents" (log only). */
    data class Uris(val uris: List<Uri>, val source: String) : SendRequest
}

/**
 * One file ready for the service (plan §4.7): [uri] is what the service opens (with `setRequireOriginal` when
 * [requireOriginal]); [original] = false means "Send without location" was chosen [H19]; [unreadable] items fail
 * with "Can't read this file any more" in the engine.
 */
data class PreparedItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val mtimeMs: Long?,
    val requireOriginal: Boolean,
    val original: Boolean = true,
    val unreadable: Boolean = false,
)

data class Prepared(
    val items: List<PreparedItem>,
    /** Ids of the photos/videos whose original could not be opened: ask "Allow / Send without location" first. */
    val needsLocationChoice: List<Long>,
    /** An image/video arrived through a provider Ferry could not map to MediaStore [A19]. */
    val loseLocationNote: Boolean = false,
    /** Content URIs whose read grant rides on the service's start intent (share / documents) [A5] [B19]. */
    val grantUris: List<Uri> = emptyList(),
)

/**
 * The UI-side entry point for every send (plan §4.3, §4.4 [PB5] [PA10]): [prepare] builds the items and probes each
 * original on `Dispatchers.IO`; the caller shows the location choice if needed ([withoutLocation] or ask for the
 * permission and prepare again); then [start] hands the items to `TransferService`. The service never has to decide
 * anything about locations.
 */
object SendCoordinator {
    suspend fun prepare(ctx: Context, req: SendRequest): Prepared = withContext(Dispatchers.IO) {
        val f = UploadItemFactory(ctx.applicationContext, AppGraph.transfers::newId)
        val hasLocation = f.hasLocationPermission()
        val ones = when (req) {
            is SendRequest.Picks -> req.picks.map { f.fromPick(it, hasLocation) }
            is SendRequest.Uris -> req.uris.map { f.fromUri(it, hasLocation) }
        }
        val grants = if (req is SendRequest.Uris) req.uris.filter { it.scheme == "content" } else emptyList()
        AppGraph.log("send", "prepared ${ones.size} item(s) from ${req.javaClass.simpleName}; location choice for " +
            "${ones.count { it.needsChoice }}; location permission=$hasLocation")
        Prepared(ones.map { it.item }, ones.filter { it.needsChoice }.map { it.item.id }, ones.any { it.loseLocation }, grants)
    }

    /** "Send without location": those items go on the plain (redacted) URI and count as "Saved without location". */
    fun withoutLocation(p: Prepared): Prepared {
        val ids = p.needsLocationChoice.toHashSet()
        return p.copy(
            items = p.items.map { if (it.id in ids) it.copy(original = false, requireOriginal = false) else it },
            needsLocationChoice = emptyList(),
        )
    }

    /** Hands the items over in-process [CP8] and starts the service from the visible screen [A6]. */
    fun start(ctx: Context, p: Prepared) {
        require(p.needsLocationChoice.isEmpty()) { "location choice not made" }
        if (p.items.isEmpty()) return
        val token = AppGraph.transfers.handOver(p.items)
        val i = Intent(ctx, TransferService::class.java)
            .setAction(TransferService.ACTION_SEND_PICKS)
            .putExtra(TransferService.EXTRA_TOKEN, token)
        if (p.grantUris.isNotEmpty()) {
            val clip = ClipData.newRawUri("ferry", p.grantUris[0])
            for (u in p.grantUris.drop(1)) clip.addItem(ClipData.Item(u))
            val withGrant = Intent(i).apply {
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                ContextCompat.startForegroundService(ctx, withGrant)
                AppGraph.log("send", "service started with the read grant for ${p.grantUris.size} URI(s)")
                return
            } catch (e: SecurityException) {
                // A provider that does not let Ferry pass its grant on: those files are read while the process
                // lives (in-process hand-over); nothing is lost, the engine marks unreadable ones as failed.
                AppGraph.log("send", "grant forwarding refused: $e")
            }
        }
        ContextCompat.startForegroundService(ctx, i)
    }

    fun sendAnyway(ctx: Context, ids: Collection<Long>) = action(ctx, TransferService.ACTION_SEND_ANYWAY) {
        putExtra(TransferService.EXTRA_IDS, ids.toLongArray())
    }

    fun retry(ctx: Context) = action(ctx, TransferService.ACTION_RETRY)

    fun cancel(ctx: Context) = action(ctx, TransferService.ACTION_CANCEL)

    private fun action(ctx: Context, action: String, extras: Intent.() -> Unit = {}) {
        ContextCompat.startForegroundService(ctx, Intent(ctx, TransferService::class.java).setAction(action).apply(extras))
    }
}
