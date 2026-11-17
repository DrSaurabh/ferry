package app.ferry.ui.common

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.ferry.AppGraph
import app.ferry.R
import app.ferry.media.Prepared
import app.ferry.media.SendCoordinator
import app.ferry.media.SendRequest
import app.ferry.ui.phone.LocationChoiceDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The UI side of every send (plan §4.4 [PB5] [H19]), shared by "Send other files" and the share activity (and the
 * picker in Ph6): "Preparing…" while `SendCoordinator.prepare()` runs, then — only if a photo's original can't be
 * read — the location choice: **Allow** asks for the photo permissions (photos, videos and photo location together,
 * `PermissionPlanner.locationRequest`, audit O1) and prepares again; if the original still can't be read the dialog
 * comes back with **Open Settings** in place of Allow; **Send without location** sends those items on the plain URI. Then `SendCoordinator.start()` and `onStarted`. Dismissing the
 * dialog sends nothing (`onCancelled`). Call [send] to begin; place [Overlay] on top of the screen.
 */
class SendFlow internal constructor() {
    internal var preparing by mutableStateOf(false)
    internal var pending by mutableStateOf<Pair<SendRequest, Prepared>?>(null)
    internal var again: SendRequest? = null
    /** **Allow** was tried in this send and the original still can't be read: the dialog now offers Settings (O1). */
    internal var askedAlready by mutableStateOf(false)
    internal lateinit var sender: (SendRequest) -> Unit
    internal lateinit var overlay: @Composable () -> Unit

    fun send(req: SendRequest) = sender(req)

    /** "Preparing…" and the location dialog; draws nothing otherwise. */
    @Composable
    fun Overlay() = overlay()
}

@Composable
fun rememberSendFlow(onStarted: (Prepared) -> Unit, onCancelled: () -> Unit = {}): SendFlow {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val started by rememberUpdatedState(onStarted)
    val cancelled by rememberUpdatedState(onCancelled)
    val flow = remember { SendFlow() }

    fun begin(p: Prepared) {
        flow.askedAlready = false
        SendCoordinator.start(ctx, p)
        if (p.loseLocationNote) Toast.makeText(ctx.applicationContext, R.string.lose_location_note, Toast.LENGTH_LONG).show()
        started(p)
    }

    flow.sender = { req ->
        if (!flow.preparing) {
            flow.preparing = true
            scope.launch {
                val p = try {
                    SendCoordinator.prepare(ctx, req)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppGraph.log("send", "prepare failed: $e")
                    null
                } finally {
                    flow.preparing = false
                }
                when {
                    p == null || p.items.isEmpty() -> {
                        flow.askedAlready = false
                        cancelled()
                    }
                    p.needsLocationChoice.isEmpty() -> begin(p)
                    else -> flow.pending = req to p
                }
            }
        }
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        AppGraph.log("send", "photo location permission: ${result[PermissionPlanner.ACCESS_MEDIA_LOCATION]} " +
            "(granted ${result.filterValues { it }.keys.map { it.substringAfterLast('.') }})")
        // Granted → originals. Still not readable → the dialog again, now with Open Settings instead of Allow:
        // Android stops showing its own dialog after two refusals, so Allow would do nothing for ever (O1).
        flow.again?.let { r ->
            flow.again = null
            flow.askedAlready = true
            flow.send(r)
        }
    }
    flow.overlay = {
        if (flow.preparing) PreparingCard()
        flow.pending?.let { (req, p) ->
            LocationChoiceDialog(
                settings = flow.askedAlready,
                onAllow = {
                    flow.pending = null
                    val perms = PermissionPlanner.locationRequest(Build.VERSION.SDK_INT)
                    if (perms.isEmpty()) flow.send(req) else { flow.again = req; askLocation.launch(perms.toTypedArray()) }
                },
                onOpenSettings = { // nothing is sent now: the user allows it in Settings and sends again
                    flow.pending = null
                    flow.askedAlready = false
                    openAppSettings(ctx)
                    cancelled()
                },
                onWithoutLocation = {
                    flow.pending = null
                    begin(SendCoordinator.withoutLocation(p))
                },
                onDismiss = {
                    flow.pending = null
                    flow.askedAlready = false
                    cancelled()
                },
            )
        }
    }
    return flow
}

/** The short "Preparing…" state while the files are opened and measured. */
@Composable
fun PreparingCard() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(Modifier.testTag("preparing")) {
            Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(R.string.preparing))
            }
        }
    }
}

/** "Sending 12 files to Saurabhs-PC" (design §4.1.7); the PC's name when known, else "your PC". */
fun sendingToast(ctx: Context, count: Int) {
    val pc = (AppGraph.connection.state.value as? app.ferry.net.ConnectionState.Connected)?.name
        ?: AppGraph.prefs.chosenName ?: ctx.getString(R.string.your_pc)
    Toast.makeText(ctx.applicationContext, ctx.resources.getQuantityString(R.plurals.share_toast, count, count, pc), Toast.LENGTH_SHORT).show()
}
