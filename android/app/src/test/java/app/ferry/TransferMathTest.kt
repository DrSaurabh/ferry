package app.ferry

import app.ferry.core.upload.EngineState
import app.ferry.core.upload.ItemRow
import app.ferry.core.upload.ItemStatus
import app.ferry.core.upload.Phase
import app.ferry.core.upload.Summary
import app.ferry.ui.common.TransferMath
import org.junit.Assert.assertEquals
import org.junit.Test

/** The numbers behind "Sending 5 of 20", the overall bar and "n failed. Tap to retry" (plan §4.12). */
class TransferMathTest {
    private fun row(id: Long, st: ItemStatus) = ItemRow(id, "f$id.jpg", 100, true, st)

    @Test
    fun positionCountsFinishedPlusOne() {
        val rows = listOf(
            row(1, ItemStatus.Saved("f1.jpg", false)),
            row(2, ItemStatus.AlreadyOnPc("f2.jpg")),
            row(3, ItemStatus.Sending(10, 100)),
            row(4, ItemStatus.Sending(5, 100)),
            row(5, ItemStatus.Waiting),
        )
        assertEquals(3 to 5, TransferMath.position(rows))
        assertEquals(1 to 1, TransferMath.position(listOf(row(1, ItemStatus.Saved("f1.jpg", false)))))
        assertEquals(0 to 0, TransferMath.position(emptyList()))
    }

    @Test
    fun fractionAndPercent() {
        val s = EngineState(Phase.Sending, emptyList(), 250, 1000, 0, null, null, null)
        assertEquals(0.25f, TransferMath.fraction(s), 0.0001f)
        assertEquals(0f, TransferMath.fraction(s.copy(totalBytes = 0)), 0f)
        assertEquals(33, TransferMath.percent(ItemStatus.Sending(1, 3)))
        assertEquals(0, TransferMath.percent(ItemStatus.Sending(1, 0)))
    }

    @Test
    fun retryableIsFailedPlusNotSent() {
        assertEquals(5, TransferMath.retryable(Summary(1, 0, 0, 0, failed = 2, notSent = 3, cancelled = 1, stop = null)))
    }
}
