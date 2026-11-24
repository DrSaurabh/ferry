package app.ferry.ui.phone

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ferry.AppGraph
import app.ferry.R
import app.ferry.ui.common.Battery
import app.ferry.ui.common.LogScreen
import app.ferry.ui.common.MediaAccess
import app.ferry.ui.common.PermissionPlanner
import app.ferry.ui.common.PhoneTopBar
import app.ferry.ui.common.mediaAccess
import app.ferry.ui.common.onClickLogged

/** What is granted now (setup skips those steps). */
fun setupGranted(ctx: Context): SetupGranted {
    val sdk = Build.VERSION.SDK_INT
    val loc = PermissionPlanner.locationPermission(sdk)
    return SetupGranted(
        media = mediaAccess(ctx) == MediaAccess.Full &&
            (loc == null || ctx.checkSelfPermission(loc) == PackageManager.PERMISSION_GRANTED),
        notifications = sdk < 33 || ctx.checkSelfPermission(PermissionPlanner.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        battery = Battery.isIgnoringOptimizations(ctx),
        installs = canInstall(ctx),
    )
}

private fun canInstall(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

/** The steps of this setup, planned once per process (rotation keeps them) and where it stands. */
class SetupViewModel(app: Application) : AndroidViewModel(app) {
    val steps: List<SetupStep> = SetupPlan.steps(Build.VERSION.SDK_INT, setupGranted(app))
    var index by mutableIntStateOf(SetupPlan.resumeAt(steps, AppGraph.prefs.firstSetupStep))
    /** A system page was opened for this step; its result is read when Ferry is back on screen. */
    var waitingFor by mutableStateOf<SetupStep?>(null)

    init {
        AppGraph.log("setup", "steps ${steps.map { it.logName }}, starting at ${steps[index].logName}")
        AppGraph.prefs.firstSetupStep = steps[index].logName
    }

    val step: SetupStep get() = steps[index]

    fun go(i: Int) {
        index = i.coerceIn(0, steps.lastIndex)
        AppGraph.prefs.firstSetupStep = steps[index].logName
    }

    fun next() = go(index + 1)
}

/**
 * First setup (U2, design §5.5, M23; replaces 2.0's Welcome): one screen per step with a big button and a small
 * **Skip** — photos & videos + location, notifications (13+), battery exemption, "Install unknown apps" (8+) — then
 * "This phone will appear on your PC as … · Change" and Home. Granted steps are skipped. Each result is logged
 * (`perm name= ask=1` / `result=`). Back = previous step; on step 1 Back leaves Ferry and setup resumes there at the
 * next start (H94). Afterwards Ferry never pops up a permission dialog by itself (H72).
 */
@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val vm: SetupViewModel = viewModel()
    val step = vm.step
    BackHandler(enabled = vm.index > 0) {
        AppGraph.event("back", "screen" to "setup_" + step.logName, "result" to "previous_step")
        vm.go(vm.index - 1)
    }
    val sdk = Build.VERSION.SDK_INT
    val mediaPerms = remember { PermissionPlanner.mediaPermissions(sdk) }
    val askMedia = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val a = mediaAccess(ctx)
        AppGraph.log("perm", "setup: media permissions → $a")
        AppGraph.event("perm", "name" to "media", "result" to if (a == MediaAccess.None) "denied" else a.name.lowercase())
        if (a == MediaAccess.None) AppGraph.prefs.mediaDenied = true
        vm.next()
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        AppGraph.event("perm", "name" to "notifications", "result" to if (ok) "granted" else "denied")
        vm.next()
    }
    // Battery and updates open a system page; its result is read when Ferry is in front again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val w = vm.waitingFor ?: return@LifecycleEventEffect
        vm.waitingFor = null
        val ok = if (w == SetupStep.Battery) Battery.isIgnoringOptimizations(ctx) else canInstall(ctx)
        AppGraph.event("perm", "name" to w.logName, "result" to if (ok) "granted" else "denied")
        if (vm.step == w) vm.next()
    }
    fun skip() {
        AppGraph.event("perm", "name" to step.logName, "result" to "skipped")
        if (step == SetupStep.Notifications) AppGraph.prefs.notificationsAsked = true
        vm.next()
    }
    fun allow() {
        AppGraph.event("perm", "name" to step.logName, "ask" to 1)
        when (step) {
            SetupStep.Media -> askMedia.launch(mediaPerms.toTypedArray())
            SetupStep.Notifications -> {
                AppGraph.prefs.notificationsAsked = true // the share screen never asks again (U2)
                askNotifications.launch(PermissionPlanner.POST_NOTIFICATIONS)
            }
            SetupStep.Battery -> {
                vm.waitingFor = SetupStep.Battery
                Battery.request(ctx)
            }
            SetupStep.Updates -> {
                vm.waitingFor = SetupStep.Updates
                openInstallSetting(ctx)
            }
            SetupStep.Name -> {}
        }
    }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState())
            .padding(16.dp).testTag("welcome"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LogScreen("setup_" + step.logName)
        PhoneTopBar(stringResource(R.string.welcome_title), showBack = vm.index > 0)
        val total = SetupPlan.numbered(vm.steps)
        if (step != SetupStep.Name) {
            Text(stringResource(R.string.setup_step, vm.index + 1, total), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.testTag("setup-step"))
        }
        if (vm.index == 0) {
            Text(stringResource(R.string.welcome_line1), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.welcome_line3), style = MaterialTheme.typography.bodyLarge)
        }
        Column(Modifier.testTag("setup-${step.logName}"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (step) {
                SetupStep.Name -> NameStep(onDone = {
                    AppGraph.prefs.welcomeDone = true
                    AppGraph.prefs.firstSetupStep = null
                    onDone()
                })
                else -> {
                    val (title, text) = when (step) {
                        SetupStep.Media -> R.string.setup_media_title to R.string.setup_media_text
                        SetupStep.Notifications -> R.string.setup_notifications_title to R.string.setup_notifications_text
                        SetupStep.Battery -> R.string.setup_battery_title to R.string.setup_battery_text
                        else -> R.string.setup_updates_title to R.string.setup_updates_text
                    }
                    Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    Text(bold(stringResource(text)), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.heightIn(min = 8.dp))
                    val big = if (step == SetupStep.Updates) R.string.setup_open else R.string.setup_allow
                    Button(onClick = onClickLogged("setup.allow_" + step.logName) { allow() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("setup-allow")) {
                        Text(stringResource(big), style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(onClick = onClickLogged("setup.skip_" + step.logName) { skip() }, Modifier.testTag("setup-skip")) {
                        Text(stringResource(R.string.setup_skip))
                    }
                }
            }
        }
    }
}

/** "This phone will appear on your PC as **vivo V2413** · Change" (M23, H85): one optional text field. */
@Composable
private fun NameStep(onDone: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(AppGraph.prefs.deviceNameOverride ?: AppGraph.deviceName) }
    fun save() {
        val n = name.trim()
        if (editing && n.isNotEmpty() && n != AppGraph.deviceName) {
            AppGraph.prefs.deviceNameOverride = n
            AppGraph.names.refresh()
        }
        onDone()
    }
    Text(stringResource(R.string.setup_name_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
    if (editing) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            label = { Text(stringResource(R.string.setup_name_field)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier.fillMaxWidth().testTag("setup-name-field"),
        )
    } else {
        Text(bold(stringResource(R.string.setup_name_text, AppGraph.deviceName)), style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("setup-name"))
        TextButton(onClick = onClickLogged("setup.name_change") { editing = true }, Modifier.testTag("setup-name-change")) {
            Text(stringResource(R.string.setup_name_change))
        }
    }
    Button(onClick = onClickLogged("setup.done") { save() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("setup-done")) {
        Text(stringResource(R.string.setup_done), style = MaterialTheme.typography.titleMedium)
    }
}

/** Android 8+ "Install unknown apps" for Ferry (`package:` URI, M16); the general list if that page is missing. */
fun openInstallSetting(ctx: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    try {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)))
    } catch (e: Exception) {
        AppGraph.log("setup", "unknown-sources page failed: $e")
        try {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
        } catch (e2: Exception) {
            AppGraph.log("setup", "unknown-sources list failed: $e2")
        }
    }
}

/** `**bold**` marks in a string resource → bold spans (the setup texts name the button to press, M23). */
fun bold(text: String): AnnotatedString = buildAnnotatedString {
    val parts = text.split("**")
    for ((i, p) in parts.withIndex()) {
        if (i % 2 == 1) {
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            append(p)
            pop()
        } else {
            append(p)
        }
    }
}
