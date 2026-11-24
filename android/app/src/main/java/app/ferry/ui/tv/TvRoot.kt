package app.ferry.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ferry.R
import app.ferry.net.ConnectionState
import app.ferry.ui.common.LogScreen
import app.ferry.ui.common.StatusDot
import app.ferry.ui.common.statusText
import app.ferry.ui.nav.Navigator
import app.ferry.ui.nav.Screen

/**
 * TV layout root (design §5.1, plan §4.9): the top screen of the back stack. Home (or "PC not found") has the
 * overscan padding [A25] and the header; the Player is full screen. Back on Home finishes the activity. A cast from the
 * phone (Ph9) is drawn instead of all of it while it lasts.
 */
@Composable
fun TvRoot(nav: Navigator, vm: TvViewModel = viewModel()) {
    val conn by vm.connection.collectAsState()
    val cast by CastSinkImpl.show.collectAsState()
    Surface(Modifier.fillMaxSize().testTag("tv-root"), color = MaterialTheme.colorScheme.background) {
        val showing = cast
        // A cast from the phone shows on top of whatever is showing (design §5.5); Back ends it (B78).
        if (showing != null) {
            LogScreen("tv_cast")
            key(showing.id) { TvCastScreen(showing) }
        } else when (val s = nav.current) {
            is Screen.TvPlayer -> TvPlayerScreen(s) { nav.pop() }
            is Screen.TvViewer -> TvViewerScreen(s, onBack = { nav.pop() })
            else -> Column(
                Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TvHeader(conn)
                when (val c = conn) {
                    is ConnectionState.Connected -> TvHomeScreen(vm, c, nav)
                    ConnectionState.Looking -> Text(statusText(c), style = MaterialTheme.typography.titleLarge)
                    else -> TvPcNotFound(vm, c)
                }
            }
        }
    }
}

@Composable
private fun TvHeader(conn: ConnectionState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val pc = (conn as? ConnectionState.Connected)?.name ?: "…"
        Text(
            stringResource(R.string.tv_header, pc),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("tv-header"),
        )
        Text("  ")
        StatusDot(conn, 18.dp)
    }
}
