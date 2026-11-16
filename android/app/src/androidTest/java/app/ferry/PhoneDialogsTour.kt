package app.ferry

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ferry.probe.Shots
import app.ferry.ui.common.MediaGate
import app.ferry.ui.common.rememberMediaGate
import app.ferry.ui.phone.LocationChoiceDialog
import app.ferry.ui.theme.FerryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phone suite, Ph10 screenshot tour: dialogs whose real trigger can't be reached at the end of the suite (photo
 * location access is granted by then; "denied twice" would make Android stop asking for good), drawn on their own
 * in a plain test activity for the wording review: "Allow / Send without location" [H19] and "Ferry can't see your
 * photos and videos → Open Settings". The real flows are tested by GpsNegativeControlTest (choice made in code) and
 * PhonePermissionTour (first refusal).
 */
@RunWith(AndroidJUnit4::class)
class PhoneDialogsTour {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String) {
        compose.waitForIdle()
        Thread.sleep(700)
        Shots.take("tour-$name")
    }

    @Test
    fun locationChoice() {
        compose.setContent { FerryTheme { LocationChoiceDialog(onAllow = {}, onWithoutLocation = {}, onDismiss = {}) } }
        shoot("location-dialog")
    }

    @Test
    fun mediaDenied() {
        compose.setContent {
            FerryTheme {
                val gate = rememberMediaGate(onReady = {})
                LaunchedEffect(Unit) { gate.ask = MediaGate.Ask.Settings }
                gate.Overlay()
            }
        }
        shoot("media-denied")
    }
}
