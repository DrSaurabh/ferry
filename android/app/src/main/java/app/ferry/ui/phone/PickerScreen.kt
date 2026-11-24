package app.ferry.ui.phone

import app.ferry.ui.common.onClickLogged
import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ferry.AppGraph
import app.ferry.R
import app.ferry.media.Album
import app.ferry.media.GridEntry
import app.ferry.media.MediaItem
import app.ferry.media.MediaPick
import app.ferry.media.MediaRepo
import app.ferry.media.MediaThumb
import app.ferry.media.PickerBack
import app.ferry.media.PickerLogic
import app.ferry.media.PickerMode
import app.ferry.media.SendRequest
import app.ferry.ui.common.Format
import app.ferry.ui.common.MediaAccess
import app.ferry.ui.common.LogDialog
import app.ferry.ui.common.MissingPermission
import app.ferry.ui.common.MissingPermissionBanner
import app.ferry.ui.common.PartialAccessBanner
import app.ferry.ui.common.PhoneTopBar
import app.ferry.ui.common.logDialog
import app.ferry.ui.common.rememberMediaGate
import app.ferry.ui.common.mediaAccess
import app.ferry.ui.common.rememberSendFlow
import app.ferry.ui.nav.Navigator
import app.ferry.ui.nav.Screen
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Everything the picker shows, built off the main thread from one MediaStore load. */
data class Library(
    val items: List<MediaItem> = emptyList(),
    val recent: List<GridEntry> = emptyList(),
    val albums: List<Album> = emptyList(),
    val byId: Map<Long, MediaItem> = emptyMap(),
)

/**
 * Picker state (plan §4.3, design §4.1.2): the library (paged MediaStore load, albums in memory), the tab, the open
 * album and the **selection** — an ordered set of MediaStore ids held here, so it survives tab and album switches
 * and files are sent in pick order [A17]. A ContentObserver marks the list stale; it reloads on the next resume.
 */
class PickerViewModel(private val app: Application, val mode: PickerMode) : ViewModel() {
    private val repo = MediaRepo(app)
    val library = MutableStateFlow(Library())
    val loading = MutableStateFlow(false)
    val selection = MutableStateFlow<Set<Long>>(LinkedHashSet())
    val albumsTab = MutableStateFlow(false)
    val openAlbum = MutableStateFlow<String?>(null)
    val access = MutableStateFlow<MediaAccess?>(null)

    @Volatile private var stale = true
    private var loadJob: Job? = null
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            stale = true
        }
    }

    init {
        for (video in listOf(false, true)) {
            app.contentResolver.registerContentObserver(MediaRepo.collection(video), true, observer)
        }
    }

    override fun onCleared() {
        app.contentResolver.unregisterContentObserver(observer)
    }

    fun onResume() {
        val a = mediaAccess(app)
        val changed = a != access.value
        access.value = a
        if (stale || changed) reload()
    }

    private fun reload() {
        stale = false
        loadJob?.cancel()
        loading.value = true
        val tz = TimeZone.getDefault()
        val today = PickerLogic.day(System.currentTimeMillis() / 1000, tz)
        val fmt = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault())
        val label = { day: Long, s: Long ->
            when (day) {
                today -> app.getString(R.string.picker_today)
                today - 1 -> app.getString(R.string.picker_yesterday)
                else -> fmt.format(Date(s * 1000))
            }
        }
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            try {
                repo.load(mode, keepGoing = { isActive }) { items ->
                    val byId = items.associateBy { it.id }
                    library.value = Library(items, PickerLogic.withDayHeaders(items, tz, label), PickerLogic.albums(items), byId)
                    selection.value = PickerLogic.prune(selection.value, byId)
                }
                AppGraph.log("picker", "loaded ${library.value.items.size} items, ${library.value.albums.size} albums " +
                    "in ${System.currentTimeMillis() - started} ms ($mode)")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // No media access (SecurityException) or a provider error: an empty picker, not a crash.
                AppGraph.log("picker", "media load failed: $e")
            } finally {
                loading.value = false
            }
        }
    }

    fun toggle(id: Long) {
        selection.value = PickerLogic.toggle(selection.value, id, mode.single)
    }

    fun selectAll(ids: List<Long>, select: Boolean) {
        selection.value = if (select) PickerLogic.selectAll(selection.value, ids) else PickerLogic.unselectAll(selection.value, ids)
    }

    fun picks(): List<MediaPick> {
        val byId = library.value.byId
        return selection.value.mapNotNull { id -> byId[id]?.let { MediaPick(it.id, it.isVideo) } }
    }

    /** Leaving the picker (Back or after Send): the next visit starts empty on Recent; the loaded rows stay. */
    fun reset() {
        selection.value = LinkedHashSet()
        albumsTab.value = false
        openAlbum.value = null
    }
}

/**
 * Photo picker (P3; plan §4.3, design §4.1.2): tabs **Recent** (newest first, date headers that scroll with the
 * photos [CP20]) and **Albums** (list → album grid with **Select all**); tap toggles; video badge with duration;
 * bottom bar "12 selected · 1.4 GB" + **Send** through the shared send flow (Preparing / location choice) [PB5].
 * Partial access shows the "Allow all" banner [A15]. Cast modes (Ph9) filter the kinds and use [onCast].
 */
@Composable
fun PickerScreen(nav: Navigator, mode: PickerMode, onCast: ((List<MediaPick>) -> Unit)? = null) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as Application
    val vm: PickerViewModel = viewModel(key = "picker-$mode") { PickerViewModel(app, mode) }
    val lib by vm.library.collectAsState()
    val loading by vm.loading.collectAsState()
    val sel by vm.selection.collectAsState()
    val albumsTab by vm.albumsTab.collectAsState()
    val openAlbum by vm.openAlbum.collectAsState()
    val access by vm.access.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    DisposableEffect(Unit) {
        onDispose { if (nav.stack.none { it is Screen.Picker }) vm.reset() }
    }
    // Back (top or system, the same path, H69): album → album list → leave; leaving with picks asks first (B1, H59).
    var askDiscard by remember { mutableStateOf(false) }
    BackHandler {
        when (PickerLogic.back(openAlbum != null, sel.size)) {
            PickerBack.CloseAlbum -> { vm.openAlbum.value = null }
            PickerBack.AskDiscard -> { askDiscard = true }
            PickerBack.Leave -> { nav.pop() }
        }
    }
    // Refused at setup (U2): a banner with Fix runs the permission flow; never a pop-up by itself.
    val gate = rememberMediaGate(onReady = { vm.onResume() })
    val flow = rememberSendFlow(onStarted = { nav.pop(); nav.push(Screen.Transfer) })
    val album = openAlbum?.let { id -> lib.albums.firstOrNull { it.bucketId == id } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("picker")) {
            val title = when (mode) {
                PickerMode.Send -> R.string.btn_send_photos
                PickerMode.CastVideo -> R.string.picker_title_video
                PickerMode.CastPhotos -> R.string.picker_title_photos
            }
            PhoneTopBar(stringResource(title), showBack = true, modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp))
            TabRow(selectedTabIndex = if (albumsTab) 1 else 0) {
                Tab(selected = !albumsTab, onClick = onClickLogged("picker.recent") { vm.albumsTab.value = false; vm.openAlbum.value = null },
                    text = { Text(stringResource(R.string.picker_recent)) })
                // Inside an album the Albums tab goes back to the album list (B1).
                Tab(selected = albumsTab, onClick = onClickLogged("picker.albums") { vm.albumsTab.value = true; vm.openAlbum.value = null },
                    text = { Text(stringResource(R.string.picker_albums)) })
            }
            if (access == MediaAccess.Partial) PartialAccessBanner(Modifier.padding(8.dp))
            if (access == MediaAccess.None) MissingPermissionBanner(MissingPermission.Media, Modifier.padding(8.dp)) { gate.open() }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !albumsTab -> MediaGrid(lib.recent, sel, vm::toggle, "grid-recent")
                    album != null -> Column {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(album.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!mode.single) {
                                val ids = lib.items.filter { it.bucketId == album.bucketId }.map { it.id }
                                val all = ids.isNotEmpty() && sel.containsAll(ids)
                                TextButton(onClick = onClickLogged("picker.select_all") { vm.selectAll(ids, !all) }, Modifier.testTag("select-all")) {
                                    Text(stringResource(if (all) R.string.picker_unselect_all else R.string.picker_select_all))
                                }
                            }
                        }
                        val cells = lib.items.filter { it.bucketId == album.bucketId }.map { GridEntry.Cell(it) }
                        MediaGrid(cells, sel, vm::toggle, "grid-album")
                    }
                    else -> AlbumList(lib.albums) { vm.openAlbum.value = it.bucketId }
                }
                if (!loading && lib.items.isEmpty() && access != MediaAccess.None) {
                    Text(stringResource(R.string.picker_empty), Modifier.align(Alignment.Center).padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (sel.isNotEmpty()) {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(12.dp).testTag("picker-bar"), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.picker_selected, sel.size, Format.size(PickerLogic.bytes(sel, lib.byId))),
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Button(onClick = onClickLogged(if (mode == PickerMode.Send) "send" else "cast") {
                        val picks = vm.picks()
                        AppGraph.log("picker", "send ${picks.size} picked item(s)")
                        AppGraph.event("pick", "count" to picks.size, "kind" to mode.name.lowercase())
                        when {
                            mode == PickerMode.Send -> flow.send(SendRequest.Picks(picks))
                            onCast != null -> onCast(picks)
                            else -> {}
                        }
                    }) { Text(stringResource(if (mode == PickerMode.Send) R.string.picker_send else R.string.picker_play)) }
                }
            }
        }
        flow.Overlay()
        gate.Overlay()
    }
    if (askDiscard) {
        LogDialog("discard_picks")
        AlertDialog(
            onDismissRequest = { logDialog("discard_picks", "dismiss"); askDiscard = false },
            modifier = Modifier.testTag("discard-dialog"),
            text = { Text(ctx.resources.getQuantityString(R.plurals.discard_picks, sel.size, sel.size), style = MaterialTheme.typography.titleMedium) },
            confirmButton = {
                TextButton(onClick = onClickLogged("discard_picks.discard") {
                    logDialog("discard_picks", "discard")
                    askDiscard = false
                    vm.reset()
                    nav.pop("discarded_picks")
                }, Modifier.testTag("discard-yes")) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = {
                TextButton(onClick = onClickLogged("discard_picks.keep") { logDialog("discard_picks", "keep"); askDiscard = false },
                    Modifier.testTag("discard-keep")) { Text(stringResource(R.string.keep_picking)) }
            },
        )
    }
}

@Composable
private fun MediaGrid(entries: List<GridEntry>, sel: Set<Long>, onToggle: (Long) -> Unit, tag: String) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        modifier = Modifier.fillMaxSize().testTag(tag),
    ) {
        items(
            entries,
            key = { it.key },
            span = { if (it is GridEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            contentType = { if (it is GridEntry.Header) 0 else 1 },
        ) { e ->
            when (e) {
                is GridEntry.Header -> Text(e.label, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp))
                is GridEntry.Cell -> MediaCell(e.item, e.item.id in sel) { onToggle(e.item.id) }
            }
        }
    }
}

@Composable
private fun MediaCell(m: MediaItem, selected: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.aspectRatio(1f).padding(1.dp)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = m.name },
    ) {
        AsyncImage(
            model = MediaThumb(m.id, m.isVideo),
            contentDescription = null,
            imageLoader = AppGraph.thumbnails,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().background(Color(0xFFE5E7EB)),
        )
        if (m.isVideo) {
            Surface(color = Color(0x99000000), contentColor = Color.White, shape = RoundedCornerShape(4.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
                Text("▶ " + PickerLogic.duration(m.durationMs), style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
            }
        }
        if (selected) {
            Box(Modifier.fillMaxSize().border(3.dp, MaterialTheme.colorScheme.primary).background(Color(0x331D4ED8)))
            Surface(color = MaterialTheme.colorScheme.primary, contentColor = Color.White, shape = CircleShape,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("✓", style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

@Composable
private fun AlbumList(albums: List<Album>, onOpen: (Album) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("album-list")) {
        items(albums, key = { it.bucketId }) { a ->
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onClickLogged("picker.album") { onOpen(a) }).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AsyncImage(
                    model = MediaThumb(a.newest.id, a.newest.isVideo),
                    contentDescription = null,
                    imageLoader = AppGraph.thumbnails,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).background(Color(0xFFE5E7EB)),
                )
                Column(Modifier.weight(1f)) {
                    Text(a.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(LocalContext.current.resources.getQuantityString(R.plurals.picker_album_count, a.count, a.count), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.size(0.dp))
            }
        }
    }
}
