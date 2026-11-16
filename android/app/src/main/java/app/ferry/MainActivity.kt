package app.ferry

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.ferry.ui.nav.Navigator
import app.ferry.ui.nav.Screen
import app.ferry.ui.phone.PhoneRoot
import app.ferry.ui.theme.FerryTheme
import app.ferry.ui.theme.TvTheme
import app.ferry.ui.tv.CastReceiverHost
import app.ferry.ui.tv.KeepScreenOn
import app.ferry.ui.tv.TvKeys
import app.ferry.ui.tv.TvRoot

/**
 * The single activity (plan §4.2): phone layout (edge-to-edge, FerryTheme) or TV layout (TvTheme).
 * Debug builds only: extras `app.ferry.debug.UI = tv|phone`, `app.ferry.debug.NET = any` [PR2] and
 * `app.ferry.debug.CAST_RECEIVER = true` (the TV's cast receiver in phone mode, for the loopback test) [B27].
 */
class MainActivity : ComponentActivity() {
    /** The back stack (tests read the top screen). */
    var nav: Navigator? = null
        private set

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
        // Receiving casts (design §5.5): TV mode; on a phone only with the debug extra (emulator loopback test, B27).
        val receiver = tv || (BuildConfig.DEBUG && intent?.getBooleanExtra(DeviceMode.EXTRA_CAST_RECEIVER, false) == true)
        AppGraph.castReceiverOn = receiver
        if (receiver) lifecycle.addObserver(CastReceiverHost(this))
        if (savedInstanceState == null) openFrom(intent)
        if (tv) {
            KeepScreenOn.poke(this) // opening Ferry counts as a key press (design §5.1 [A10])
            setContent { TvTheme { TvRoot(nav) } }
        } else {
            enableEdgeToEdge()
            setContent { FerryTheme { PhoneRoot(nav) } }
        }
    }

    /** TV: every key press keeps the screen on for another 30 min on Home (design §5.1 [A10] [H24]). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (AppGraph.isTv && event.action == KeyEvent.ACTION_DOWN) KeepScreenOn.poke(this)
        // Player / Viewer keys go to the screen itself, whatever has focus (B74).
        if (AppGraph.isTv && TvKeys.dispatch(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /** singleTask: a notification tap while Ferry is open arrives here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFrom(intent)
    }

    /** Extra `open = transfer | fromPc | remote` (from the notifications) pushes that screen (plan §4.2) [A24]. */
    private fun openFrom(i: Intent?) {
        if (AppGraph.isTv) return
        when (i?.getStringExtra(EXTRA_OPEN)) {
            OPEN_TRANSFER -> nav?.show(Screen.Transfer)
            OPEN_FROM_PC -> nav?.show(Screen.FromPc)
            OPEN_REMOTE -> nav?.show(Screen.Remote)
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
        const val OPEN_REMOTE = "remote"
    }
}
