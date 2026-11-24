package app.ferry

import app.ferry.media.PickerBack
import app.ferry.media.PickerLogic
import org.junit.Assert.assertEquals
import org.junit.Test

/** B1/U1 (H59, H69): Back in the picker — album → album list → leave, asking before picks are thrown away. */
class PickerBackTest {
    @Test fun openAlbumClosesToTheListAndKeepsPicks() {
        assertEquals(PickerBack.CloseAlbum, PickerLogic.back(albumOpen = true, picks = 0))
        assertEquals(PickerBack.CloseAlbum, PickerLogic.back(albumOpen = true, picks = 7))
    }

    @Test fun leavingWithPicksAsks() {
        assertEquals(PickerBack.AskDiscard, PickerLogic.back(albumOpen = false, picks = 1))
        assertEquals(PickerBack.AskDiscard, PickerLogic.back(albumOpen = false, picks = 7))
    }

    @Test fun leavingWithoutPicksLeaves() {
        assertEquals(PickerBack.Leave, PickerLogic.back(albumOpen = false, picks = 0))
    }

    @Test fun backTwiceFromAnAlbumWithPicks() {
        // album grid → album list (picks kept) → "Discard N picks?"
        var albumOpen = true
        val picks = 3
        val first = PickerLogic.back(albumOpen, picks)
        if (first == PickerBack.CloseAlbum) albumOpen = false
        assertEquals(PickerBack.CloseAlbum, first)
        assertEquals(PickerBack.AskDiscard, PickerLogic.back(albumOpen, picks))
    }
}
