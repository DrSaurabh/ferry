package app.ferry.ui.common

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.ferry.AppGraph
import app.ferry.R
import app.ferry.ui.theme.FerryAmber

/** The permissions a screen can miss after setup (U2, H72, PO16b). */
enum class MissingPermission(val logName: String) { Media("media"), Notifications("notifications") }

/**
 * One line on the screen that needs a refused permission, with **Fix** (U2): no pop-up of its own. Media (in the
 * picker): [onFix] runs the media permission flow (Android's dialog while it still shows it, else Ferry's Settings
 * page). Notifications (Transfer screen): Ferry's notification settings page.
 */
@Composable
fun MissingPermissionBanner(perm: MissingPermission, modifier: Modifier = Modifier, onFix: () -> Unit) {
    LogError(stringResource(if (perm == MissingPermission.Media) R.string.missing_media else R.string.missing_notifications))
    Surface(
        color = FerryAmber,
        contentColor = Color.Black,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth().testTag("missing-${perm.logName}"),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(if (perm == MissingPermission.Media) R.string.missing_media else R.string.missing_notifications),
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            )
            TextButton(onClick = onClickLogged("banner.fix_" + perm.logName, onFix), Modifier.testTag("missing-${perm.logName}-fix")) {
                Text(stringResource(R.string.fix), color = Color.Black)
            }
        }
    }
}

/** Android 13+ notifications are off for Ferry? */
fun notificationsMissing(ctx: Context): Boolean =
    Build.VERSION.SDK_INT >= 33 &&
        ctx.checkSelfPermission(PermissionPlanner.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED

/** Ferry's notification settings (API 26+), else its app page. */
fun openNotificationSettings(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Exception) {
        AppGraph.log("perm", "can't open notification settings: $e")
        openAppSettings(ctx)
    }
}
