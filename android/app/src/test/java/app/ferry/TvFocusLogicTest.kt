package app.ferry

import app.ferry.ui.tv.RowFocus
import app.ferry.ui.tv.TvFocusLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan §4.13: TV focus memory, row/index clamping after the list changes. */
class TvFocusLogicTest {
    private val rows = listOf("videos" to 3, "photos" to 6, "apps" to 1, "other" to 2)

    @Test fun firstVisitIsFirstCardOfFirstNonEmptyRow() {
        assertEquals(RowFocus("videos", 0), TvFocusLogic.target(null, rows))
        assertEquals(RowFocus("photos", 0), TvFocusLogic.target(null, listOf("videos" to 0) + rows.drop(1)))
    }

    @Test fun rememberedCardIsKept() {
        assertEquals(RowFocus("photos", 4), TvFocusLogic.target(RowFocus("photos", 4), rows))
    }

    @Test fun indexIsClampedWhenTheRowShrank() {
        assertEquals(RowFocus("videos", 2), TvFocusLogic.target(RowFocus("videos", 7), rows))
        assertEquals(RowFocus("videos", 0), TvFocusLogic.target(RowFocus("videos", -1), rows))
    }

    @Test fun goneRowFallsBackToFirstNonEmptyRow() {
        val changed = listOf("videos" to 0, "photos" to 2, "other" to 1)
        assertEquals(RowFocus("photos", 0), TvFocusLogic.target(RowFocus("apps", 0), changed))
        assertEquals(RowFocus("photos", 0), TvFocusLogic.target(RowFocus("videos", 1), changed))
    }

    @Test fun noCardsNoTarget() {
        assertNull(TvFocusLogic.target(RowFocus("videos", 1), emptyList()))
        assertNull(TvFocusLogic.target(null, listOf("videos" to 0)))
    }

    @Test fun existsChecksRowAndRange() {
        assertTrue(TvFocusLogic.exists(RowFocus("other", 1), rows))
        assertFalse(TvFocusLogic.exists(RowFocus("other", 2), rows))
        assertFalse(TvFocusLogic.exists(RowFocus("music", 0), rows))
        assertFalse(TvFocusLogic.exists(null, rows))
    }
}
