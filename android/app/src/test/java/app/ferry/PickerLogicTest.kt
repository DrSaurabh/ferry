package app.ferry

import app.ferry.media.GridEntry
import app.ferry.media.MediaItem
import app.ferry.media.PagedStream
import app.ferry.media.PickerLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Picker logic (plan §4.3, §4.7): paging rules, merge order, albums, day headers, ordered selection. */
class PickerLogicTest {
    private fun item(id: Long, date: Long, video: Boolean = false, bucket: String? = "b", size: Long = 10) =
        MediaItem(id, video, "f$id", size, date, bucket, bucket?.uppercase())

    /** A fake table of [n] rows (ids n..1, newest first), honouring or ignoring limit/offset. */
    private fun table(n: Int, honours: Boolean): (Int, Int) -> List<MediaItem> {
        val rows = (n downTo 1).map { item(it.toLong(), it.toLong()) }
        return { offset, limit -> if (honours) rows.drop(offset).take(limit) else rows }
    }

    @Test
    fun pagingReadsEveryRowOnce() {
        var calls = 0
        val t = table(1234, honours = true)
        val s = PagedStream(500) { o, l -> calls++; t(o, l) }
        while (!s.done) s.next()
        assertEquals(1234, s.items.size)
        assertEquals(3, calls) // 500 + 500 + 234 (< 500 → done)
        assertEquals(s.items.map { it.id }.toSet().size, 1234)
    }

    @Test
    fun exactMultipleOfThePageStopsOnTheEmptyPage() {
        var calls = 0
        val t = table(1000, honours = true)
        val s = PagedStream(500) { o, l -> calls++; t(o, l) }
        while (!s.done) s.next()
        assertEquals(1000, s.items.size)
        assertEquals(3, calls)
    }

    @Test
    fun platformIgnoringTheLimitStopsWhenNothingNewArrives() {
        var calls = 0
        val t = table(1234, honours = false)
        val s = PagedStream(500) { o, l -> calls++; t(o, l) }
        while (!s.done) s.next()
        assertEquals(1234, s.items.size)
        assertEquals(2, calls)
    }

    @Test
    fun loadMergedPublishesFirstPageAtOnceAndEndsNewestFirst() {
        val images = PagedStream(2) { o, l -> listOf(item(1, 100), item(3, 300), item(5, 50)).sortedByDescending { it.dateModifiedS }.drop(o).take(l) }
        val videos = PagedStream(2) { o, l -> listOf(item(2, 200, video = true), item(4, 400, video = true)).drop(o).take(l) }
        val published = ArrayList<List<Long>>()
        PickerLogic.loadMerged(listOf(images, videos), keepGoing = { true }, every = 100) { published.add(it.map { m -> m.id }) }
        assertEquals(listOf(4L, 3L, 2L, 1L), published.first())
        assertEquals(2, published.size)
        assertEquals(listOf(4L, 3L, 2L, 1L, 5L), published.last())
    }

    @Test
    fun loadMergedStopsWhenAsked() {
        var n = 0
        val s = PagedStream(1) { o, _ -> n++; listOf(item(o.toLong() + 1, 1)) }
        var rounds = 0
        PickerLogic.loadMerged(listOf(s), keepGoing = { rounds++ < 3 }) {}
        assertEquals(3, n)
    }

    @Test
    fun albumsAreCountedAndNewestFirst() {
        val items = listOf(item(1, 10, bucket = "a"), item(2, 30, bucket = "b"), item(3, 20, bucket = "a"), item(4, 5, bucket = null))
        val a = PickerLogic.albums(items)
        assertEquals(listOf("b", "a"), a.map { it.bucketId })
        assertEquals(2, a[1].count)
        assertEquals(3L, a[1].newest.id)
        assertEquals("A", a[1].name)
    }

    @Test
    fun dayHeadersBeforeEachNewDay() {
        val utc = TimeZone.getTimeZone("UTC")
        val d = 86_400L
        val items = listOf(item(1, 3 * d + 10), item(2, 3 * d + 5), item(3, 2 * d + 7))
        val e = PickerLogic.withDayHeaders(items, utc) { day, _ -> "day$day" }
        assertEquals(listOf("h3", "m1", "m2", "h2", "m3"), e.map { it.key })
        assertEquals("day3", (e[0] as GridEntry.Header).label)
        // Local time: 23:30 UTC on day 2 is already day 3 in UTC+1
        val plus1 = TimeZone.getTimeZone("GMT+01:00")
        assertEquals(3L, PickerLogic.day(2 * d + 23 * 3600 + 1800, plus1))
    }

    @Test
    fun selectionKeepsPickOrderAndSurvivesUnrelatedChanges() {
        var sel: Set<Long> = emptySet()
        sel = PickerLogic.toggle(sel, 5, single = false)
        sel = PickerLogic.toggle(sel, 2, single = false)
        sel = PickerLogic.selectAll(sel, listOf(7, 2, 9))
        assertEquals(listOf(5L, 2L, 7L, 9L), sel.toList())
        sel = PickerLogic.toggle(sel, 2, single = false)
        assertEquals(listOf(5L, 7L, 9L), sel.toList())
        sel = PickerLogic.unselectAll(sel, listOf(7, 9))
        assertEquals(listOf(5L), sel.toList())
        val byId = mapOf(5L to item(5, 1, size = 1500), 8L to item(8, 1))
        assertEquals(1500L, PickerLogic.bytes(sel, byId))
        assertEquals(listOf(5L), PickerLogic.prune(linkedSetOf(9L, 5L), byId).toList())
    }

    @Test
    fun singleModeKeepsOne() {
        var sel = PickerLogic.toggle(emptySet(), 1, single = true)
        sel = PickerLogic.toggle(sel, 2, single = true)
        assertEquals(setOf(2L), sel)
        assertTrue(PickerLogic.toggle(sel, 2, single = true).isEmpty())
    }

    @Test
    fun durationBadge() {
        assertEquals("0:07", PickerLogic.duration(6_600))
        assertEquals("12:34", PickerLogic.duration(754_000))
        assertEquals("1:02:03", PickerLogic.duration(3_723_000))
    }
}
