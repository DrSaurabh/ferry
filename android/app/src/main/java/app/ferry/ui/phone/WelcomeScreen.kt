package app.ferry.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import app.ferry.ui.common.Battery

/** First run (design §4.2, plan §4.3): what Ferry does + the one battery step [P10] [H23]. No other dialogs. */
@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var ignoring by remember { mutableStateOf(Battery.isIgnoringOptimizations(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ignoring = Battery.isIgnoringOptimizations(ctx) }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState())
            .padding(24.dp).testTag("welcome"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.welcome_line1), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.welcome_line2), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.welcome_line3), style = MaterialTheme.typography.bodyLarge)
        if (!ignoring) {
            Card(Modifier.fillMaxWidth().testTag("battery-card")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.battery_card), style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = {
                        AppGraph.prefs.batteryAsked = true
                        Battery.request(ctx)
                    }) { Text(stringResource(R.string.battery_allow)) }
                }
            }
        }
        Button(
            onClick = {
                AppGraph.prefs.welcomeDone = true
                onDone()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.continue_)) }
    }
}
