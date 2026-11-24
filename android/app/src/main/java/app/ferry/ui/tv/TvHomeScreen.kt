package app.ferry.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.ferry.AppGraph
import app.ferry.R
import app.ferry.core.model.OutFile
import app.ferry.net.ConnectionState
import app.ferry.player.ResumeRule
import app.ferry.ui.common.Format
import app.ferry.ui.common.UpdateBanner
import app.ferry.ui.common.rememberInstallFlow
import app.ferry.ui.common.tvClickable
import app.ferry.ui.nav.Navigator
import app.ferry.ui.nav.Screen
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What OK on a card does. */
enum class CardKind { Video, Slideshow, Photo, Apk, Other }

/** One card of a TV home row; [key] keeps the list diff stable across the 5 s refresh (outbox id). */
class TvCardData(val key: Any, val kind: CardKind, val title: String, val sub: String, val file: OutFile?)

class TvRowData(val key: String, val title: String, val note: String?, val cards: List<TvCardData>)

/**
 * TV home (design §5.1, plan §4.9) [T1] [T4]: update banner (focusable), idle line + this TV's name [A10], then the
 * outbox rows Videos (`video_playable`) / Photos (Slideshow card + thumbnails) / Apps / Other files (incl. `video`)
 * [A12], or the empty state; refreshed every 5 s while visible. Every card is a [tvClickable] (one focus stop, one
 * click per OK [PA14]). **Focus memory** [A13]: the focused card is kept in [TvViewModel.focus]; when Home is shown
 * again (back from the Player) the card is scrolled into view and gets focus back; on the first visit the first
 * card of the first non-empty row has it; if the outbox changed under it, the position is clamped ([TvFocusLogic]).
 */
@Composable
fun TvHomeScreen(vm: TvViewModel, conn: ConnectionState.Connected, nav: Navigator) {
    val ctx = LocalContext.current
    val outbox by vm.outbox.collectAsState()
    val update by vm.update.collectAsState()
    val installing by vm.installing.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(conn) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.refreshOutbox(conn)
                delay(5_000)
            }
        }
    }
    val rows = remember(outbox) { buildRows(outbox?.files.orEmpty(), ctx) }
    // The Photos row's files in row order, for the viewer and the slideshow (T3).
    val photos = remember(outbox, conn) {
        outbox?.files.orEmpty().filter { it.kind == "image" }
            .map { TvPhoto(it.name, conn.api.absoluteUrl(it.url), ResumeRule.key(it.name, it.size, it.mtime)) }
    }
    val counts = rows.map { it.key to it.cards.size }
    val empty = outbox != null && rows.isEmpty()

    // LazyColumn items: [empty state], then one item per row.
    val firstRowItem = if (empty) 1 else 0
    val column = rememberLazyListState()
    val rowStates = remember { mutableMapOf<String, LazyListState>() }
    val requesters = remember { mutableMapOf<RowFocus, FocusRequester>() }
    var placed by remember { mutableStateOf(false) }

    LaunchedEffect(counts) {
        if (placed && TvFocusLogic.exists(vm.focus, counts)) return@LaunchedEffect
        val t = TvFocusLogic.target(vm.focus, counts) ?: return@LaunchedEffect
        val rowIndex = rows.indexOfFirst { it.key == t.row }
        column.scrollToItem(firstRowItem + rowIndex)
        withFrameNanos {}
        rowStates[t.row]?.scrollToItem(t.index)
        withFrameNanos {}
        // A card that was just scrolled in may need one more frame before its requester is attached (plan §6 Ph8b risks).
        for (attempt in 1..5) {
            val ok = runCatching { requesters.getValue(t).requestFocus() }.isSuccess
            if (ok) break
            withFrameNanos {}
        }
        placed = true
        AppGraph.log("tv", "focus placed on ${t.row}/${t.index}")
    }

    // The installer has returned (the activity resumes after a pause during which it was open): empty files/apk.
    val installer = rememberInstallFlow()
    var openedAtPause by remember { mutableIntStateOf(0) }
    var cleaned by remember { mutableIntStateOf(installer.installerOpened) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { openedAtPause = installer.installerOpened }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (openedAtPause > cleaned) {
                cleaned = openedAtPause
                withContext(Dispatchers.IO) { emptyApkFolder() }
            }
        }
    }

    fun onCard(row: String, i: Int, c: TvCardData) {
        vm.clicks.incrementAndGet()
        AppGraph.log("tv", "OK on $row/$i ${c.title}")
        val f = c.file
        when (c.kind) {
            CardKind.Video -> if (f != null) nav.push(Screen.TvPlayer(f.name, conn.api.absoluteUrl(f.url), ResumeRule.key(f.name, f.size, f.mtime)))
            CardKind.Apk -> if (f != null) vm.installing.value = f
            CardKind.Slideshow -> if (photos.isNotEmpty()) nav.push(Screen.TvViewer(photos, 0, slideshow = true))
            CardKind.Photo -> {
                val start = photos.indexOfFirst { it.key == f?.let { x -> ResumeRule.key(x.name, x.size, x.mtime) } }
                if (start >= 0) nav.push(Screen.TvViewer(photos, start, slideshow = false))
            }
            CardKind.Other -> {} // "Open these on your phone or PC": no action [A12]
        }
    }

    // The banner sits above the list, so it is on screen whenever it exists (it may arrive after focus was placed).
    UpdateBanner(update, Modifier.onFocusChanged { vm.bannerFocused = it.isFocused })
    // The idle line stays on screen too [A10]: a focused lower row scrolling the list must not hide it (Ph8a run 2).
    Column {
        Text(stringResource(R.string.tv_ready), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("tv-idle"))
        TvIdentityLine() // this TV's name and address (audit O2)
    }
    LazyColumn(state = column, verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.testTag("tv-home")) {
        if (empty) item(key = "empty") { Text(stringResource(R.string.tv_empty), style = MaterialTheme.typography.titleMedium) }
        for (r in rows) {
            item(key = "row-${r.key}") {
                val state = rowStates.getOrPut(r.key) { LazyListState() }
                TvRow(r, state, conn, { i -> requesters.getOrPut(RowFocus(r.key, i)) { FocusRequester() } },
                    onFocus = { i, c, has -> vm.onCardFocus(RowFocus(r.key, i), c.title, has) }, onClick = { i, c -> onCard(r.key, i, c) }) // tap-id: tv_home.<kind> (TvCard)
            }
        }
    }
    installing?.let { f -> TvInstallDialog(f, installer) { vm.installing.value = null } }
    installer.Overlay()
}

/** Splits the outbox into the four TV rows [A12]; Photos starts with the Slideshow card. */
private fun buildRows(files: List<OutFile>, ctx: android.content.Context): List<TvRowData> {
    val res = ctx.resources
    fun card(kind: CardKind, f: OutFile) = TvCardData(f.id, kind, f.name, Format.size(f.size), f)
    val videos = files.filter { it.kind == "video_playable" }.map { card(CardKind.Video, it) }
    val photos = files.filter { it.kind == "image" }
    val apps = files.filter { it.kind == "apk" }.map { card(CardKind.Apk, it) }
    val other = files.filter { it.kind != "video_playable" && it.kind != "image" && it.kind != "apk" }.map { card(CardKind.Other, it) }
    val rows = mutableListOf<TvRowData>()
    if (videos.isNotEmpty()) rows += TvRowData("videos", res.getString(R.string.tv_row_videos), null, videos)
    if (photos.isNotEmpty()) {
        val cards = listOf(TvCardData("slideshow", CardKind.Slideshow, res.getString(R.string.tv_slideshow_card, photos.size), "", null)) +
            photos.map { card(CardKind.Photo, it) }
        rows += TvRowData("photos", res.getString(R.string.tv_row_photos), null, cards)
    }
    if (apps.isNotEmpty()) rows += TvRowData("apps", res.getString(R.string.tv_row_apps), null, apps)
    if (other.isNotEmpty()) rows += TvRowData("other", res.getString(R.string.tv_row_other), res.getString(R.string.tv_other_files), other)
    return rows
}

@Composable
private fun TvRow(
    r: TvRowData,
    state: LazyListState,
    conn: ConnectionState.Connected,
    requester: (Int) -> FocusRequester,
    onFocus: (Int, TvCardData, Boolean) -> Unit,
    onClick: (Int, TvCardData) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(r.title, style = MaterialTheme.typography.titleMedium)
        if (r.note != null) Text(r.note, style = MaterialTheme.typography.bodySmall)
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), // room for the ring and the 1.05 scale
            modifier = Modifier.testTag("row-${r.key}"),
        ) {
            itemsIndexed(r.cards, key = { _, c -> c.key }) { i, c ->
                val m = Modifier
                    .testTag("card-${r.key}-$i")
                    .focusRequester(requester(i))
                if (c.kind == CardKind.Photo && c.file != null) {
                    TvPhotoCard("tv_home.photo", m, c.file, conn, { onFocus(i, c, it) }) { onClick(i, c) }
                } else {
                    TvCard("tv_home." + c.kind.name.lowercase(), m, c.title, c.sub, { onFocus(i, c, it) }) { onClick(i, c) }
                }
            }
        }
    }
}

/** A text card (video, slideshow, APK, other file) with the TV focus ring (plan §4.9). */
@Composable
fun TvCard(id: String, modifier: Modifier, title: String, sub: String, onFocus: (Boolean) -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = shape,
        modifier = modifier.size(300.dp, 130.dp).tvClickable(id = id, shape, onFocus, onClick),
    ) {
        Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * A photo card: the thumbnail (loaded only while the card is composed, [TvThumbnails]) under the file name; a photo
 * that can't be decoded shows a warning sign instead [A11].
 */
@Composable
private fun TvPhotoCard(id: String, modifier: Modifier, f: OutFile, conn: ConnectionState.Connected, onFocus: (Boolean) -> Unit, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(10.dp)
    var failed by remember(f.id) { mutableStateOf(false) }
    val request = remember(f.id, f.size, f.mtime) { TvThumbnails.request(ctx, conn.api.absoluteUrl(f.url), f) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = shape,
        modifier = modifier.size(220.dp, 130.dp).tvClickable(id = id, shape, onFocus, onClick),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (failed) {
                Text("⚠", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.align(Alignment.Center).testTag("thumb-error"))
            } else {
                AsyncImage(
                    model = request,
                    contentDescription = f.name,
                    imageLoader = TvThumbnails.loader(ctx),
                    contentScale = ContentScale.Crop,
                    onError = {
                        failed = true
                        AppGraph.log("tv", "thumbnail failed: ${f.name}: ${it.result.throwable}")
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                f.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x99000000)).padding(6.dp),
            )
        }
    }
}
