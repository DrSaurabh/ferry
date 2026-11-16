package app.ferry.ui.common

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
import app.ferry.AppGraph
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
    // The TV says "this TV", the phone "this phone" (Ph10 carry note).
    is ConnectionState.NotFound -> stringResource(if (AppGraph.isTv) R.string.status_not_found_tv else R.string.status_not_found)
    ConnectionState.NoWifi -> stringResource(if (AppGraph.isTv) R.string.status_no_wifi_tv else R.string.status_no_wifi)
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
