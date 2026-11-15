package app.ferry.ui.common

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ferry.BuildConfig
import app.ferry.R
import app.ferry.net.ConnectionState
import app.ferry.ui.theme.StatusGreen
import app.ferry.ui.theme.StatusGrey
import app.ferry.ui.theme.StatusRed

/** The status line for Home and the TV header (plan §4.12). */
@Composable
fun statusText(s: ConnectionState): String = when (s) {
    ConnectionState.Looking -> stringResource(R.string.status_looking)
    is ConnectionState.Connected -> stringResource(R.string.status_connected, s.name)
    is ConnectionState.NotFound -> stringResource(R.string.status_not_found)
    ConnectionState.NoWifi -> stringResource(R.string.status_no_wifi)
    is ConnectionState.OldPc -> stringResource(R.string.status_old_pc)
}

fun statusColor(s: ConnectionState): Color = when (s) {
    is ConnectionState.Connected -> StatusGreen
    ConnectionState.Looking -> StatusGrey
    else -> StatusRed
}

@Composable
fun StatusDot(s: ConnectionState, size: Dp = 12.dp) {
    Box(Modifier.size(size).background(statusColor(s), CircleShape))
}

/** Buttons of later phases (plan §6 Ph4): a toast on debug builds; release builds keep them disabled. */
fun comingSoon(ctx: Context) {
    if (BuildConfig.DEBUG) Toast.makeText(ctx, R.string.coming_soon, Toast.LENGTH_SHORT).show()
}
