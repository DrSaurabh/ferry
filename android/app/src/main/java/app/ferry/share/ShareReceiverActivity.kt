package app.ferry.share

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import app.ferry.AppGraph
import app.ferry.R
import app.ferry.media.SendRequest
import app.ferry.ui.common.rememberSendFlow
import app.ferry.ui.common.sendingToast
import app.ferry.ui.theme.FerryTheme

/**
 * Share → Ferry (design §4.1.7, plan §6 Ph5b) [A5] [B19]: a translucent activity with no screen of its own.
 * 1. The shared URIs ([ShareIntents.parse]); this activity holds their read grant while it lives.
 * 2. `SendCoordinator.prepare()` ("Preparing…"), and the location choice when a photo's original can't be read
 *    [PB5] [H19] — the only case where the activity stays up.
 * 3. `SendCoordinator.start()` from this visible activity (allowed to start a foreground service, API 34+): the
 *    items are handed over in-process **and** the URIs ride in the service's start intent `ClipData` with
 *    `FLAG_GRANT_READ_URI_PERMISSION` (B45), so the service holds its own grant after this activity is gone
 *    (proven by `ShareToFerryTest`).
 * 4. Toast "Sending 3 files to Saurabhs-PC", finish: the user is back in the Gallery; progress is in the
 *    notification. A PC that is not found yet is waited for by the engine.
 * TV: Ferry does not send from a TV (T6), so a share there only finishes.
 */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = ShareIntents.parse(intent)
        AppGraph.log("share", "share ${intent?.action}: ${uris.size} URI(s)")
        if (AppGraph.isTv || uris.isEmpty()) {
            if (uris.isEmpty()) Toast.makeText(applicationContext, R.string.share_nothing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        AppGraph.connection.start() // look for the PC while the files are prepared
        setContent {
            FerryTheme {
                val flow = rememberSendFlow(
                    onStarted = { p ->
                        sendingToast(this, p.items.size)
                        finish()
                    },
                    onCancelled = { finish() },
                )
                flow.Overlay()
                LaunchedEffect(Unit) { flow.send(SendRequest.Uris(uris, "share")) }
            }
        }
    }

    override fun onDestroy() {
        AppGraph.log("share", "share activity destroyed")
        super.onDestroy()
    }
}
