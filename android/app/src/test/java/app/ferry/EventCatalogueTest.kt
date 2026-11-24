package app.ferry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `docs/v2.1-log-events.md` against the code (plan §2.1, CP136 [PO1, PO15], E4):
 * (a) every event name written by code (`event("…"` / `detailed("…"`) is in part 1;
 * (b) every part-1 event is written somewhere in `:core` or `:app` `src/main`, unless it is in [PENDING] (events of
 *     later phases; each phase removes what it now emits, empty by Ph19) or [NOT_VIA_EVENT];
 * (c) all three parts parse.
 */
class EventCatalogueTest {
    private val doc = File(RepoFiles.root, "docs/v2.1-log-events.md").readText()

    private fun table(part: String): List<List<String>> {
        val body = doc.substringAfter("<!-- $part:start -->").substringBefore("<!-- $part:end -->")
        return body.lines().map { it.trim() }.filter { it.startsWith("|") }.drop(2) // header + separator
            .map { row -> row.trim('|').split(Regex("""(?<!\\)\|""")).map { it.trim() } }
    }

    private val catalogue: Set<String> by lazy { table("events").map { it[0] }.toSet() }

    private val used: Map<String, String> by lazy {
        val call = Regex("""\b(?:event|detailed)\(\s*"([^"]+)"""")
        val out = LinkedHashMap<String, String>()
        val files = RepoFiles.kotlinFiles("android/core/src/main/kotlin") + RepoFiles.kotlinFiles("android/app/src/main/java")
        for (f in files) for (m in call.findAll(f.readText())) out.putIfAbsent(m.groupValues[1], f.name)
        out
    }

    @Test fun allThreePartsParse() {
        val events = table("events")
        assertTrue(events.size >= 40)
        events.forEach { assertEquals("events row $it", 4, it.size); assertTrue(it[3] == "N" || it[3] == "D") }
        val outcomes = table("outcomes")
        assertTrue(outcomes.isNotEmpty())
        outcomes.forEach { assertEquals("outcomes row $it", 5, it.size) }
        val signals = table("signals")
        assertEquals(11, signals.size)
        signals.forEach { assertEquals("signals row $it", 3, it.size) }
    }

    @Test fun everyEventInCodeIsCatalogued() {
        val missing = used.filterKeys { it !in catalogue }
        assertTrue("events used in code but not in docs/v2.1-log-events.md part 1: $missing", missing.isEmpty())
    }

    @Test fun everyCataloguedEventIsEmittedOrPending() {
        val silent = catalogue - used.keys - PENDING - NOT_VIA_EVENT
        assertTrue("catalogued events nobody writes (emit them or add to PENDING with their phase): $silent", silent.isEmpty())
    }

    @Test fun pendingHoldsOnlyUnwrittenCataloguedEvents() {
        assertTrue("PENDING has unknown events: ${PENDING - catalogue}", catalogue.containsAll(PENDING))
        val done = PENDING.intersect(used.keys)
        assertTrue("now emitted, remove from PENDING: $done", done.isEmpty())
    }

    companion object {
        /** Catalogued events of later phases (plan §3); emptied phase by phase, empty by Ph19. */
        val PENDING = setOf(
            "share.resolve", // Ph14
            "send.queued", "send.resume", "work.run", // Ph15
            "ctl.rx", "caps", "volume", // Ph16
            "zoom", // Ph17a
            "doc", // Ph17b
            "ctl.tx", "ctl.ack", "poll.start", "poll.stop", // Ph18
        )

        /** Written outside `EventLog.event` (LogReader's `gap`, M8). */
        val NOT_VIA_EVENT = setOf("gap")
    }
}
