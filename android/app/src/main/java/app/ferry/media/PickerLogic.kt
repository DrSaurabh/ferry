package app.ferry.media

import java.util.Locale
import java.util.TimeZone

/** One photo or video row from MediaStore (plan §4.7 projection). Pure Kotlin so the picker logic is unit-tested. */
data class MediaItem(
    val id: Long,
    val isVideo: Boolean,
    val name: String,
    val size: Long,
    val dateModifiedS: Long,
    val bucketId: String?,
    val bucketName: String?,
    val durationMs: Long = 0,
)

/** An album (MediaStore bucket), built in memory from the rows [A17] (Android 11+ has no GROUP BY). */
data class Album(val bucketId: String, val name: String, val count: Int, val newest: MediaItem)

/** Picker modes (plan §4.3): Send = photos and videos, many; CastVideo = one video; CastPhotos = photos, many. */
enum class PickerMode(val images: Boolean, val videos: Boolean, val single: Boolean) {
    Send(true, true, false),
    CastVideo(false, true, true),
    CastPhotos(true, false, false),
}

/** A grid entry of the Recent tab: a full-width date header that scrolls with the photos [CP20], or a photo. */
sealed interface GridEntry {
    val key: String

    data class Header(val day: Long, val label: String) : GridEntry {
        override val key: String get() = "h$day"
    }

    data class Cell(val item: MediaItem) : GridEntry {
        override val key: String get() = "m${item.id}"
    }
}

/**
 * One MediaStore table read page by page (plan §4.7 [PA12] [PB23]). Self-protecting whatever the platform does with
 * the limit/offset: rows are de-duplicated by `_ID`, and the stream ends when a page brings fewer than [pageSize]
 * rows or no new `_ID` (a platform that ignores the limit returns everything twice, then stops).
 */
class PagedStream(private val pageSize: Int, private val fetch: (offset: Int, limit: Int) -> List<MediaItem>) {
    val items = ArrayList<MediaItem>()
    private val seen = HashSet<Long>()
    private var offset = 0
    var done = false
        private set

    fun next() {
        if (done) return
        val page = fetch(offset, pageSize)
        var fresh = 0
        for (m in page) if (seen.add(m.id)) { items.add(m); fresh++ }
        offset += page.size
        if (page.size < pageSize || fresh == 0) done = true
    }
}

object PickerLogic {
    const val PAGE = 500

    private val newestFirst = compareByDescending<MediaItem> { it.dateModifiedS }.thenByDescending { it.id }

    /** Newest first by DATE_MODIFIED (the date Ferry sends) [CP11]; ties by id. */
    fun merge(lists: List<List<MediaItem>>): List<MediaItem> = lists.flatten().sortedWith(newestFirst)

    /**
     * Reads the streams round by round (one page of each), publishing the merged list after the first round (so the
     * first page shows at once), every [every] rounds, and at the end. [keepGoing] false stops early.
     */
    fun loadMerged(streams: List<PagedStream>, keepGoing: () -> Boolean, every: Int = 4, publish: (List<MediaItem>) -> Unit) {
        var round = 0
        while (streams.any { !it.done } && keepGoing()) {
            for (s in streams) s.next()
            round++
            if (round == 1 || round % every == 0 || streams.all { it.done }) publish(merge(streams.map { it.items }))
        }
        if (round == 0) publish(emptyList())
    }

    /** Albums, the one with the newest item first; rows without a bucket are left out of the album list. */
    fun albums(items: List<MediaItem>): List<Album> {
        val map = LinkedHashMap<String, MutableList<MediaItem>>()
        for (m in items) {
            val b = m.bucketId ?: continue
            map.getOrPut(b) { ArrayList() }.add(m)
        }
        return map.map { (b, list) ->
            val newest = list.minWith(newestFirst)
            Album(b, list.firstNotNullOfOrNull { it.bucketName } ?: "Other", list.size, newest)
        }.sortedWith(compareByDescending<Album> { it.newest.dateModifiedS }.thenBy { it.name })
    }

    /** Local calendar day of a DATE_MODIFIED (seconds). */
    fun day(dateModifiedS: Long, tz: TimeZone): Long {
        val ms = dateModifiedS * 1000
        return Math.floorDiv(ms + tz.getOffset(ms), 86_400_000L)
    }

    /** The Recent grid: a header before each new day ([items] are newest first). */
    fun withDayHeaders(items: List<MediaItem>, tz: TimeZone, label: (day: Long, dateModifiedS: Long) -> String): List<GridEntry> {
        val out = ArrayList<GridEntry>(items.size + 64)
        var last = Long.MIN_VALUE
        for (m in items) {
            val d = day(m.dateModifiedS, tz)
            if (d != last) {
                out.add(GridEntry.Header(d, label(d, m.dateModifiedS)))
                last = d
            }
            out.add(GridEntry.Cell(m))
        }
        return out
    }

    /** Tap toggles; the selection is an ordered set (pick order = send order) [A17]. Single mode keeps one. */
    fun toggle(sel: Set<Long>, id: Long, single: Boolean): Set<Long> {
        if (single) return if (id in sel) LinkedHashSet() else linkedSetOf(id)
        val out = LinkedHashSet(sel)
        if (!out.remove(id)) out.add(id)
        return out
    }

    /** "Select all" of an album: adds the ones not yet selected, in the album's order. */
    fun selectAll(sel: Set<Long>, ids: List<Long>): Set<Long> = LinkedHashSet(sel).apply { addAll(ids) }

    fun unselectAll(sel: Set<Long>, ids: List<Long>): Set<Long> = LinkedHashSet(sel).apply { removeAll(ids.toHashSet()) }

    /** After a reload: drops ids that are gone, keeps the order of the rest. */
    fun prune(sel: Set<Long>, present: Map<Long, MediaItem>): Set<Long> = sel.filterTo(LinkedHashSet()) { it in present }

    fun bytes(sel: Set<Long>, byId: Map<Long, MediaItem>): Long = sel.sumOf { byId[it]?.size ?: 0L }

    /** Video badge: "0:07", "12:34", "1:02:03". */
    fun duration(ms: Long): String {
        val s = (ms + 500) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
    }
}
