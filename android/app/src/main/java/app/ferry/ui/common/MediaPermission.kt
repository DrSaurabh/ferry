package app.ferry.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.ferry.AppGraph
import app.ferry.R
import app.ferry.ui.theme.FerryAmber

/** The media access Ferry has now (Full / Partial "Select photos" / None), from the granted permissions (plan §4.6). */
fun mediaAccess(ctx: Context): MediaAccess {
    val sdk = Build.VERSION.SDK_INT
    val granted = PermissionPlanner.mediaPermissions(sdk)
        .filter { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }.toSet()
    return PermissionPlanner.access(granted, sdk)
}

/** Ferry's page in system Settings (permissions live there) — the fallback after two refusals and for "Allow all". */
fun openAppSettings(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Exception) {
        AppGraph.log("perm", "can't open app settings: $e")
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * "Send photos & videos" permission flow (design §4.2, plan §4.6) [A15] [H23]: with Full or Partial access [open]
 * goes straight on; otherwise the rationale "Choose Allow all…" → one request for photos, videos and photo location
 * → Full/Partial: on; None the second time (Android no longer shows its dialog) → a button to Ferry's Settings page.
 */
class MediaGate internal constructor() {
    internal enum class Ask { Rationale, Settings }

    internal var ask by mutableStateOf<Ask?>(null)
    internal lateinit var opener: () -> Unit
    internal lateinit var overlay: @Composable () -> Unit

    fun open() = opener()

    @Composable
    fun Overlay() = overlay()
}

@Composable
fun rememberMediaGate(onReady: () -> Unit): MediaGate {
    val ctx = LocalContext.current
    val ready by rememberUpdatedState(onReady)
    val gate = remember { MediaGate() }
    val perms = remember { PermissionPlanner.mediaPermissions(Build.VERSION.SDK_INT) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val a = mediaAccess(ctx)
        AppGraph.log("perm", "media permissions ${result.filterValues { it }.keys.map { it.substringAfterLast('.') }} → $a")
        if (a != MediaAccess.None) {
            gate.ask = null
            ready()
        } else {
            val activity = ctx.findActivity()
            val systemWillAsk = activity != null && activity.shouldShowRequestPermissionRationale(perms[0])
            // Denied twice: Android stops showing its dialog, so only Settings can change it.
            gate.ask = if (AppGraph.prefs.mediaDenied && !systemWillAsk) MediaGate.Ask.Settings else MediaGate.Ask.Rationale
            AppGraph.prefs.mediaDenied = true
        }
    }
    gate.opener = {
        if (mediaAccess(ctx) != MediaAccess.None) ready() else gate.ask = MediaGate.Ask.Rationale
    }
    gate.overlay = {
        when (gate.ask) {
            MediaGate.Ask.Rationale -> AlertDialog(
                onDismissRequest = { gate.ask = null },
                text = { Text(stringResource(R.string.media_rationale), Modifier.testTag("media-rationale")) },
                confirmButton = {
                    TextButton(onClick = { request.launch(perms.toTypedArray()) }) { Text(stringResource(R.string.continue_)) }
                },
                dismissButton = { TextButton(onClick = { gate.ask = null }) { Text(stringResource(R.string.cancel)) } },
            )
            MediaGate.Ask.Settings -> AlertDialog(
                onDismissRequest = { gate.ask = null },
                text = { Text(stringResource(R.string.media_denied), Modifier.testTag("media-denied")) },
                confirmButton = {
                    TextButton(onClick = { gate.ask = null; openAppSettings(ctx) }) { Text(stringResource(R.string.open_settings)) }
                },
                dismissButton = { TextButton(onClick = { gate.ask = null }) { Text(stringResource(R.string.cancel)) } },
            )
            null -> {}
        }
    }
    return gate
}

/** "Ferry can only see some photos → Allow all" [A15]: opens Ferry's permission settings. */
@Composable
fun PartialAccessBanner(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Surface(
        color = FerryAmber,
        contentColor = Color.Black,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth().testTag("partial-access").clickable { openAppSettings(ctx) },
    ) {
        Text(stringResource(R.string.partial_access), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(12.dp))
    }
}
