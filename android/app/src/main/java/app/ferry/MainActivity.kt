package app.ferry

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.ferry.ui.nav.Navigator
import app.ferry.ui.nav.Screen
import app.ferry.ui.phone.PhoneRoot
import app.ferry.ui.theme.FerryTheme
import app.ferry.ui.theme.TvTheme
import app.ferry.ui.tv.TvRoot

/**
 * The single activity (plan §4.2): phone layout (edge-to-edge, FerryTheme) or TV layout (TvTheme).
 * Debug builds only: extras `app.ferry.debug.UI = tv|phone` and `app.ferry.debug.NET = any` [PR2] [B27].
 */
class MainActivity : ComponentActivity() {
    private var nav: Navigator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG && intent?.getStringExtra(DeviceMode.EXTRA_NET) == "any") {
            AppGraph.network.anyNetworkOverride = true
        }
        val override = if (BuildConfig.DEBUG) intent?.getStringExtra(DeviceMode.EXTRA_UI) else null
        val tv = DeviceMode.isTv(this, override)
        AppGraph.isTv = tv
        val start = when {
            tv -> Screen.TvHome
            AppGraph.prefs.welcomeDone -> Screen.Home
            else -> Screen.Welcome
        }
        val nav = Navigator(start)
        this.nav = nav
        if (savedInstanceState == null) openFrom(intent)
        if (tv) {
            setContent { TvTheme { TvRoot(nav) } }
        } else {
            enableEdgeToEdge()
            setContent { FerryTheme { PhoneRoot(nav) } }
        }
    }

    /** singleTask: a notification tap while Ferry is open arrives here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFrom(intent)
    }

    /** Extra `open = transfer | fromPc` (from the notifications) pushes that screen (plan §4.2) [A24]. */
    private fun openFrom(i: Intent?) {
        if (AppGraph.isTv) return
        when (i?.getStringExtra(EXTRA_OPEN)) {
            OPEN_TRANSFER -> nav?.show(Screen.Transfer)
            OPEN_FROM_PC -> nav?.show(Screen.FromPc)
        }
    }

    override fun onStart() {
        super.onStart()
        AppGraph.connection.start()
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_TRANSFER = "transfer"
        const val OPEN_FROM_PC = "fromPc"
    }
}
