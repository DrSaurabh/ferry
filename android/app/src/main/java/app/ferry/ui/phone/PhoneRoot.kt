package app.ferry.ui.phone

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.ferry.ui.nav.Navigator
import app.ferry.ui.nav.Screen

/** Phone layout root (plan §4.2): the screen on top of the back stack. */
@Composable
fun PhoneRoot(nav: Navigator) {
    Surface(Modifier.fillMaxSize().testTag("phone-root"), color = MaterialTheme.colorScheme.background) {
        when (val s = nav.current) {
            Screen.Welcome -> WelcomeScreen(onDone = { nav.replaceAll(Screen.Home) })
            Screen.Settings -> SettingsScreen(nav)
            Screen.Transfer -> TransferScreen(nav)
            Screen.FromPc -> FromPcScreen(nav)
            is Screen.Picker -> PickerScreen(nav, s.mode)
            else -> HomeScreen(nav)
        }
    }
}
