package app.ferry.ui.common

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
 * the user comes back (`ON_RESUME`) Ferry checks again and, if it is allowed now, starts the install it was asked for.
 */
class InstallFlow internal constructor(private val ctx: Context) {
    private var pending by mutableStateOf<Uri?>(null)
    private var askAllow by mutableStateOf(false)
    private var showSteps by mutableStateOf(false)
    /** The user was sent to the settings (or shown the steps): check again on return. */
    private var away = false

    fun install(apk: Uri) {
        if (ApkInstaller.canInstall(ctx)) {
            pending = null
            ApkInstaller.install(ctx, apk)
        } else {
            AppGraph.log("install", "not allowed to install apps yet: asking")
            pending = apk
            showSteps = false
            askAllow = true
        }
    }

    internal fun onResume() {
        val p = pending ?: return
        if (!away) return
        away = false
        if (ApkInstaller.canInstall(ctx)) {
            AppGraph.log("install", "allowed now: installing")
            pending = null
            askAllow = false
            ApkInstaller.install(ctx, p)
        } else {
            askAllow = true
        }
    }

    private fun openSettings() {
        away = true
        if (ApkInstaller.openUnknownSourcesSettings(ctx)) {
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
        AlertDialog(
            onDismissRequest = { dismiss() },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.install_allow))
                    if (showSteps) Text(stringResource(stepsText()), Modifier.testTag("install-steps"))
                }
            },
            confirmButton = {
                if (showSteps) {
                    TextButton(onClick = { dismiss() }) { Text(stringResource(R.string.ok)) }
                } else {
                    TextButton(onClick = { openSettings() }, Modifier.testTag("install-open-settings")) {
                        Text(stringResource(R.string.open_settings))
                    }
                }
            },
            dismissButton = {
                if (!showSteps) TextButton(onClick = { away = false; dismiss() }) { Text(stringResource(R.string.cancel)) }
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
