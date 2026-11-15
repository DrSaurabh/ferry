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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ferry.R
import app.ferry.net.ConnectionState
import app.ferry.ui.common.StatusDot
import app.ferry.ui.common.statusText
import app.ferry.ui.nav.Navigator

/** TV layout root (design §5.1, plan §4.9): overscan padding [A25], header, then Home or "PC not found". */
@Composable
fun TvRoot(nav: Navigator, vm: TvViewModel = viewModel()) {
    val conn by vm.connection.collectAsState()
    Surface(Modifier.fillMaxSize().testTag("tv-root"), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TvHeader(conn)
            when (val c = conn) {
                is ConnectionState.Connected -> TvHomeScreen(vm, c)
                ConnectionState.Looking -> Text(statusText(c), style = MaterialTheme.typography.titleLarge)
                else -> TvPcNotFound(vm, c)
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
