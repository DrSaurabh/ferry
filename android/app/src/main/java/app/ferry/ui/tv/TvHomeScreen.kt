package app.ferry.ui.tv

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.ferry.AppGraph
import app.ferry.R
import app.ferry.net.ConnectionState
import app.ferry.ui.common.Format
import app.ferry.ui.common.UpdateBanner
import app.ferry.ui.common.comingSoon
import app.ferry.ui.theme.FerryAmber
import kotlinx.coroutines.delay

/**
 * TV home skeleton (plan §6 Ph4, design §5.1): update banner, idle line, the outbox as plain rows (Videos,
 * Photos, Apps, Other files [A12]), empty state. Thumbnails, focus memory, player and install come in Ph8a/b.
 */
@Composable
fun TvHomeScreen(vm: TvViewModel, conn: ConnectionState.Connected) {
    val ctx = LocalContext.current
    val outbox by vm.outbox.collectAsState()
    val update by vm.update.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(conn) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.refreshOutbox(conn)
                delay(5_000)
            }
        }
    }
    val files = outbox?.files.orEmpty()
    val videos = files.filter { it.kind == "video_playable" }
    val photos = files.filter { it.kind == "image" }
    val apps = files.filter { it.kind == "apk" }
    val other = files.filter { it.kind != "video_playable" && it.kind != "image" && it.kind != "apk" }

    val slideshowLabel = stringResource(R.string.tv_slideshow_card, photos.size)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.testTag("tv-home")) {
        item { UpdateBanner(update, onClick = { comingSoon(ctx) }) }
        item {
            Column {
                Text(stringResource(R.string.tv_ready), style = MaterialTheme.typography.titleLarge)
                Text(AppGraph.deviceName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (outbox != null && files.isEmpty()) {
            item { Text(stringResource(R.string.tv_empty), style = MaterialTheme.typography.titleMedium) }
        }
        if (videos.isNotEmpty()) item { TvRow("videos", stringResource(R.string.tv_row_videos), null, videos.map { it.name to Format.size(it.size) }) { comingSoon(ctx) } }
        if (photos.isNotEmpty()) {
            val cards = listOf(slideshowLabel to "") + photos.map { it.name to Format.size(it.size) }
            item { TvRow("photos", stringResource(R.string.tv_row_photos), null, cards) { comingSoon(ctx) } }
        }
        if (apps.isNotEmpty()) item { TvRow("apps", stringResource(R.string.tv_row_apps), null, apps.map { it.name to Format.size(it.size) }) { comingSoon(ctx) } }
        if (other.isNotEmpty()) item { TvRow("other", stringResource(R.string.tv_row_other), stringResource(R.string.tv_other_files), other.map { it.name to Format.size(it.size) }, null) }
    }
}

@Composable
private fun TvRow(key: String, title: String, note: String?, cards: List<Pair<String, String>>, onClick: ((Int) -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.testTag("row-$key")) {
            itemsIndexed(cards) { i, (name, sub) ->
                TvCard(Modifier.testTag("card-$key-$i"), name, sub, onClick?.let { f -> { f(i) } })
            }
        }
    }
}

/** A focusable card with the TV focus ring (3 dp amber + scale 1.05, plan §4.9). Ph8a adds focus memory. */
@Composable
fun TvCard(modifier: Modifier, title: String, sub: String, onClick: (() -> Unit)?) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = shape,
        modifier = modifier
            .size(300.dp, 130.dp)
            .scale(if (focused) 1.05f else 1f)
            .onFocusChanged { focused = it.isFocused }
            .border(3.dp, if (focused) FerryAmber else Color.Transparent, shape)
            .clickable(enabled = true, onClick = { onClick?.invoke() }),
    ) {
        Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall)
        }
    }
}
