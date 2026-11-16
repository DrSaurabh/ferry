package app.ferry.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ferry.AppGraph
import app.ferry.media.PickerMode
import app.ferry.ui.nav.Navigator
import app.ferry.ui.nav.Screen
import app.ferry.ui.tv.CastSinkImpl
import app.ferry.ui.tv.TvCastScreen

/** Phone layout root (plan §4.2): the screen on top of the back stack. */
@Composable
fun PhoneRoot(nav: Navigator) {
    val ctx = LocalContext.current
    val castVm: CastViewModel = viewModel() // shared by Play on TV, the cast pickers and the Remote
    Surface(Modifier.fillMaxSize().testTag("phone-root"), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            when (val s = nav.current) {
                Screen.Welcome -> WelcomeScreen(onDone = { nav.replaceAll(Screen.Home) })
                Screen.Settings -> SettingsScreen(nav)
                Screen.Transfer -> TransferScreen(nav)
                Screen.FromPc -> FromPcScreen(nav)
                Screen.PlayOnTv -> PlayOnTvScreen(nav, castVm)
                Screen.Remote -> RemoteScreen(nav, castVm)
                is Screen.Picker -> PickerScreen(
                    nav, s.mode,
                    onCast = if (s.mode == PickerMode.Send) null else { picks ->
                        castVm.start(ctx, s.mode, picks) {
                            nav.pop() // the picker
                            nav.show(Screen.Remote)
                        }
                    },
                )
                else -> HomeScreen(nav)
            }
            if (AppGraph.castReceiverOn) LoopbackTv()
        }
    }
}

/**
 * Debug loopback only (`app.ferry.debug.CAST_RECEIVER`, [B27], B78): this phone is also the "TV"; what the TV would
 * show is drawn small in the top corner, over the phone's own screens.
 */
@Composable
private fun BoxScope.LoopbackTv() {
    val show by CastSinkImpl.show.collectAsState()
    val s = show ?: return
    Box(
        // Top right: over the screen titles, clear of the Remote's buttons and the picker's bottom bar.
        Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 8.dp).size(192.dp, 108.dp).background(Color.Black)
            .testTag("loopback-tv"),
    ) {
        key(s.id) { TvCastScreen(s) }
    }
}
