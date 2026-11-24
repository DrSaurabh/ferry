package app.ferry.ui.common

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.ferry.AppGraph
import app.ferry.R
import app.ferry.install.ApkInstaller

/**
 * Installing an APK from a screen (design §4.6, §5.4, plan §4.8) [A14]: if Ferry may install apps, the system
 * installer opens at once; otherwise "Allow Ferry to install apps." with **Open Settings** (Ferry's "Install unknown
 * apps" screen); if that screen does not exist (some Fire OS builds, Fire OS 6), the steps are shown as text. When
 * the user comes back (`ON_RESUME`) Ferry checks again and, if it is allowed now, starts the install it was asked for
 * (if not, it waits quietly; tapping Update / Install asks again).
 */
class InstallFlow internal constructor(private val ctx: Context) {
    private var pending by mutableStateOf<Uri?>(null)
    private var askAllow by mutableStateOf(false)
    private var showSteps by mutableStateOf(false)
    /** The user was sent to the settings (or shown the steps): check again on return. */
    private var away = false

    companion object {
        /** TV: the dialog's main button has the focus (tests read this; UiAutomator can't see Compose focus well). */
        @Volatile var mainFocused = false
    }

    /** How often the system installer was opened by this flow (TV: `files/apk` is emptied after it returns). */
    var installerOpened by mutableStateOf(0)
        private set

    private fun openInstaller(apk: Uri) {
        if (ApkInstaller.install(ctx, apk)) installerOpened++
    }

    fun install(apk: Uri) {
        if (ApkInstaller.canInstall(ctx)) {
            pending = null
            openInstaller(apk)
        } else {
            AppGraph.log("install", "not allowed to install apps yet: asking")
            pending = apk
            showSteps = false
            mainFocused = false
            askAllow = true
        }
    }

    /**
     * Back in Ferry after the settings: allowed now → install. Still not allowed → nothing is shown again (the
     * request is kept; a new tap on Update / Install asks again). Ph7 B66: a resume can also come right after
     * Open Settings, before the user has changed anything, and must not hide the later real return.
     */
    internal fun onResume() {
        val p = pending ?: return
        if (!away) return
        val ok = ApkInstaller.canInstall(ctx)
        AppGraph.log("install", "back in Ferry: allowed=$ok")
        if (ok) {
            away = false
            pending = null
            askAllow = false
            openInstaller(p)
        }
    }

    private fun openSettings() {
        away = true
        val opened = ApkInstaller.openUnknownSourcesSettings(ctx)
        AppGraph.log("install", "unknown-sources screen opened=$opened")
        if (opened) {
            askAllow = false
        } else {
            showSteps = true // no such screen: the steps as text [A14]
        }
    }

    private fun dismiss() {
        askAllow = false
        showSteps = false
        if (!away) pending = null
    }

    @Composable
    fun Overlay() {
        if (!askAllow) return
        val tv = AppGraph.isTv
        // TV: the main button (Open Settings, or OK under the steps) has the focus first, so one OK press goes on
        // (Ph10 carry note: Cancel used to be focused first). Visible amber ring as on every TV button.
        val main = remember { FocusRequester() }
        val ring = if (tv) Modifier.tvFocusRing(RoundedCornerShape(50)) else Modifier
        val mainRing = if (tv) Modifier.tvFocusRing(RoundedCornerShape(50)) { mainFocused = it }.focusRequester(main) else Modifier
        if (tv) LaunchedEffect(showSteps) { requestFocusSoon(main) { mainFocused } }
        AlertDialog(
            onDismissRequest = { dismiss() },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (tv) R.string.install_allow_tv else R.string.install_allow))
                    if (showSteps) Text(stringResource(stepsText()), Modifier.testTag("install-steps"))
                }
            },
            confirmButton = {
                if (showSteps) {
                    TextButton(onClick = onClickLogged("install_allow.ok") { dismiss() }, mainRing) { Text(stringResource(R.string.ok)) }
                } else {
                    TextButton(onClick = onClickLogged("install_allow.open_settings") { openSettings() }, mainRing.testTag("install-open-settings")) {
                        Text(stringResource(R.string.open_settings))
                    }
                }
            },
            dismissButton = {
                if (!showSteps) TextButton(onClick = onClickLogged("install_allow.cancel") { away = false; dismiss() }, ring) { Text(stringResource(R.string.cancel)) }
            },
            modifier = Modifier.testTag("install-allow"),
        )
    }

    private fun stepsText(): Int = when {
        !AppGraph.isTv -> R.string.install_steps_phone
        Build.VERSION.SDK_INT < 26 -> R.string.install_steps_fireos6
        else -> R.string.install_steps_fireos
    }
}

@Composable
fun rememberInstallFlow(): InstallFlow {
    val ctx = LocalContext.current
    val flow = remember(ctx) { InstallFlow(ctx) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { flow.onResume() }
    return flow
}
