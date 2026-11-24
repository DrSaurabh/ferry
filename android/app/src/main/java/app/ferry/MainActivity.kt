package app.ferry

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.ferry.media.SendCoordinator
import app.ferry.net.ConnectionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.ferry.service.Notifications
import app.ferry.ui.common.TapLog
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
        AppGraph.names.refresh() // the layout may have changed (debug override): TV vs phone name chain
        val nav = Navigator(
            start,
            onScreen = {
                TapLog.screen = it
                AppGraph.event("screen", "name" to it)
            },
            onBack = { s, r -> AppGraph.event("back", "screen" to s, "result" to r) },
        )
        this.nav = nav
        // Receiving casts (design §5.5): TV mode; on a phone only with the debug extra (emulator loopback test, B27).
        val receiver = tv || (BuildConfig.DEBUG && intent?.getBooleanExtra(DeviceMode.EXTRA_CAST_RECEIVER, false) == true)
        AppGraph.castReceiverOn = receiver
        if (receiver) lifecycle.addObserver(CastReceiverHost(this))
        if (savedInstanceState == null) openFrom(intent)
        // "PC not found" looks again by itself while Ferry is on screen (audit O16): phone 10 s for a minute, then
        // 60 s, reset when Ferry comes to the front again (M19); the TV every 10 s.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(ConnectionManager.RETRY_TICK_MS)
                    AppGraph.connection.retryIfNotFound()
                }
            }
        }
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
        if (AppGraph.isTv && event.action == KeyEvent.ACTION_DOWN) {
            KeepScreenOn.poke(this)
            if (event.repeatCount == 0) {
                AppGraph.detailed("key", "code" to KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_").lowercase(), "src" to "remote")
            }
        }
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
        if (AppGraph.isTv || i == null) return
        val open = i.getStringExtra(EXTRA_OPEN)
        if (open != null && (i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
            AppGraph.event("notif", "name" to open, "tapped" to 1, "action" to if (i.getBooleanExtra(EXTRA_RETRY, false)) "retry" else "open")
        }
        when (open) {
            OPEN_TRANSFER -> {
                // Not when the task is reopened from Recents with the notification's old intent.
                val fromHistory = (i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
                if (i.getBooleanExtra(EXTRA_RETRY, false) && !fromHistory) retryFromNotification()
                nav?.show(Screen.Transfer)
            }
            OPEN_FROM_PC -> nav?.show(Screen.FromPc)
            OPEN_REMOTE -> nav?.show(Screen.Remote)
        }
    }

    /**
     * Retry on the Results notification (audit O5). The queue lives in memory [H16]: if Ferry was closed since the
     * notification was posted there is nothing to retry, and the Transfer screen says so ("send the files again").
     */
    private fun retryFromNotification() {
        Notifications.cancelResults(this)
        val t = AppGraph.transfers
        if (!t.hasQueue) {
            AppGraph.log("transfer", "Retry from the notification, but the queue is gone (Ferry was closed since)")
            t.retryLost.value = true
            return
        }
        try {
            SendCoordinator.retry(this)
        } catch (e: Exception) { // a service start refused: the Transfer screen has its own Retry button
            AppGraph.log("transfer", "Retry from the notification not started: $e")
        }
    }

    override fun onResume() {
        super.onResume()
        AppGraph.connection.resetRetry() // M19: the fast first minute again
    }

    override fun onStart() {
        super.onStart()
        AppGraph.names.refresh() // a rename in the system settings while Ferry was away
        AppGraph.connection.start()
    }

    override fun onDestroy() {
        if (isFinishing) AppGraph.event("app.stop", "reason" to "finish")
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OPEN = "open"
        /** With `open = transfer`: the Results notification's Retry was tapped. */
        const val EXTRA_RETRY = "retry"
        const val OPEN_TRANSFER = "transfer"
        const val OPEN_FROM_PC = "fromPc"
        const val OPEN_REMOTE = "remote"
    }
}
