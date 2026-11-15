package app.ferry.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.ferry.R
import app.ferry.net.UpdateState
import app.ferry.ui.theme.FerryAmber

/**
 * "Ferry 2.0.7 is available → Update" (design §4.6) [C3]. Focusable (TV). The download + install flow comes in
 * Ph7; until then [onClick] is a placeholder.
 */
@Composable
fun UpdateBanner(state: UpdateState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (state !is UpdateState.Available) return
    var focused by remember { mutableStateOf(false) }
    Surface(
        color = FerryAmber,
        contentColor = Color.Black,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("update-banner")
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Text(
            stringResource(R.string.update_available, state.info.versionName),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}
