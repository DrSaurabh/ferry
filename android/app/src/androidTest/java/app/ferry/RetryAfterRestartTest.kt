package app.ferry

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import app.ferry.service.Notifications
import app.ferry.ui.nav.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phone suite, Stage 7 audit O5: **Retry** on the Results notification after Ferry's process has gone (the host
 * force-stops the app before every class, so this process has no upload queue [H16]). The notification's own intent
 * (`Notifications.retryIntent`) opens the Transfer screen, which says that the list is gone and to send again,
 * instead of starting a service that silently does nothing. Checks are on app state [B57].
 */
@RunWith(AndroidJUnit4::class)
class RetryAfterRestartTest {
    @Test
    fun retryWithoutAQueueSaysSendAgain() {
        LaunchHelpers.cleanPrefs(welcomeDone = true)
        assertFalse("a fresh process has no queue", AppGraph.transfers.hasQueue)
        assertFalse(AppGraph.transfers.retryLost.value)
        ActivityScenario.launch<MainActivity>(Notifications.retryIntent(Probe.context)).use { sc ->
            SendHelpers.waitUntil("retry reported as lost", 15_000) { AppGraph.transfers.retryLost.value }
            var top: Screen? = null
            sc.onActivity { a -> top = a.nav?.current }
            assertEquals("the Transfer screen is shown", Screen.Transfer, top)
            assertNull("nothing was queued", AppGraph.transfers.state.value)
            assertFalse("no upload service work was started", AppGraph.transfers.busy)
            SendHelpers.settle()
            Shots.take("transfer-retry-lost")
            Probe.record("o5_retry_lost", AppGraph.ringLog.snapshot().lastOrNull { it.contains("Retry from the notification") })
        }
    }
}
