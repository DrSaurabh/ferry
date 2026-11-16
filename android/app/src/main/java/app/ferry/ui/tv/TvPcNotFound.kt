package app.ferry.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.ferry.AppGraph
import app.ferry.R
import app.ferry.core.HostPort
import app.ferry.net.BroadcastAddress
import app.ferry.net.ConnectionState
import app.ferry.ui.common.requestFocusSoon
import app.ferry.ui.common.statusText
import app.ferry.ui.common.tvFocusRing

/**
 * "Can't find your PC" on the TV (design §5.1 [A18], plan §4.9): Try again, the PCs that answered, Enter
 * address with the number keyboard prefilled with the TV's first three octets. Also shows "not on Wi-Fi" and
 * "older Ferry" in the same place.
 */
@Composable
fun TvPcNotFound(vm: TvViewModel, conn: ConnectionState) {
    val first = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    fun save() {
        val hp = HostPort.parse(text)
        if (hp == null) {
            bad = true
            return
        }
        AppGraph.prefs.manual = hp
        AppGraph.log("tv", "manual address: $hp")
        editing = false
        AppGraph.connection.refresh()
    }
    DisposableEffect(Unit) { onDispose { vm.pcNotFoundFocus = null } }
    LaunchedEffect(Unit) { requestFocusSoon(first) { vm.pcNotFoundFocus == "try" } }
    val pill = RoundedCornerShape(50)
    fun ring(name: String) = Modifier.tvFocusRing(pill) { has ->
        if (has) vm.pcNotFoundFocus = name else if (vm.pcNotFoundFocus == name) vm.pcNotFoundFocus = null
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.testTag("tv-pc-not-found")) {
        Text(stringResource(R.string.tv_pc_not_found), style = MaterialTheme.typography.headlineMedium)
        Text(statusText(conn), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { AppGraph.connection.refresh() }, modifier = ring("try").focusRequester(first)) {
                Text(stringResource(R.string.try_again))
            }
            OutlinedButton(onClick = {
                text = BroadcastAddress.firstThreeOctets(AppGraph.network.ownIpv4)
                bad = false
                editing = true
            }, modifier = ring("enter")) { Text(stringResource(R.string.enter_address)) }
        }
        val candidates = (conn as? ConnectionState.NotFound)?.candidates.orEmpty()
        if (candidates.isNotEmpty()) {
            Text(stringResource(R.string.tv_found_pcs), style = MaterialTheme.typography.titleMedium)
            for (p in candidates) {
                OutlinedButton(onClick = {
                    AppGraph.prefs.manual = HostPort(p.host, p.port)
                    AppGraph.connection.refresh()
                }, modifier = ring("pc-${p.host}")) { Text("${p.name}  ${p.host}:${p.port}") }
            }
        }
        if (editing) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        bad = false
                    },
                    isError = bad,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.width(420.dp).testTag("tv-address-field"),
                )
                Button(onClick = { save() }, modifier = ring("save")) { Text(stringResource(R.string.settings_save)) }
            }
            if (bad) Text(stringResource(R.string.settings_address_bad), color = MaterialTheme.colorScheme.error)
        }
    }
}
