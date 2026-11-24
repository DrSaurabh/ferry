package app.ferry.ui.phone

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.ferry.AppGraph
import app.ferry.R
import app.ferry.service.StopCard
import app.ferry.service.StopCardRule
import app.ferry.ui.common.Battery
import app.ferry.ui.common.onClickLogged

/**
 * "Your phone stopped the send" (U9, M23, H87; replaces 2.0's vivo card on Home): shown on Home and the Transfer
 * screen only after a send was cut off ([StopCardRule]); vivo steps on vivo phones, generic battery steps
 * elsewhere, a drawn illustration (no vivo screenshot). **OK** hides it until the next cut-off send.
 */
@Composable
fun StopCardView(where: String) {
    val interrupted by AppGraph.interrupted.collectAsState()
    val card = StopCardRule.decide(interrupted, AppGraph.lastExit, Build.MANUFACTURER ?: "")
    if (card == StopCard.None) return
    val ctx = LocalContext.current
    var batteryOk by remember { mutableStateOf(Battery.isIgnoringOptimizations(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { batteryOk = Battery.isIgnoringOptimizations(ctx) }
    Card(Modifier.fillMaxWidth().testTag("stop-card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (card != StopCard.Plain) {
                    Image(
                        painterResource(if (card == StopCard.Vivo) R.drawable.stop_card_vivo else R.drawable.stop_card_generic),
                        contentDescription = null, modifier = Modifier.size(56.dp).padding(end = 12.dp),
                    )
                }
                Text(
                    stringResource(if (card == StopCard.Plain) R.string.stop_card_title_plain else R.string.stop_card_title),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("stop-card-title"),
                )
            }
            Text(ctx.resources.getQuantityString(R.plurals.stop_card_unsent, interrupted, interrupted), style = MaterialTheme.typography.bodyMedium)
            when (card) {
                StopCard.Vivo -> Text(stringResource(R.string.stop_card_vivo_steps), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("stop-card-vivo"))
                StopCard.Generic -> Text(stringResource(R.string.stop_card_generic_steps), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("stop-card-generic"))
                else -> {}
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (card != StopCard.Plain && !batteryOk) {
                    OutlinedButton(onClick = onClickLogged("$where.stop_card_battery") { Battery.request(ctx) }) { Text(stringResource(R.string.stop_card_battery)) }
                }
                TextButton(onClick = onClickLogged("$where.stop_card_ok") { dismissStopCard() }, Modifier.testTag("stop-card-ok")) { Text(stringResource(R.string.ok)) }
            }
        }
    }
}

/** The user has seen the card [A8]: hidden until a send is cut off again. */
fun dismissStopCard() {
    AppGraph.interrupted.value = 0
    if (!AppGraph.transfers.busy) AppGraph.prefs.interruptedCount = 0
}
