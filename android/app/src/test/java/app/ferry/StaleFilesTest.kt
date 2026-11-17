package app.ferry

import app.ferry.data.StaleFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Stage 7 audit S5: the start-up cleanup keeps a file the installer may still be reading. */
class StaleFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(dir: File, name: String, mtime: Long) = File(dir, name).apply {
        writeText("x")
        assertTrue(setLastModified(mtime))
    }

    @Test
    fun keepsRecentFilesDeletesOldOnes() {
        val dir = tmp.newFolder("apk")
        val now = 1_800_000_000_000L
        val fresh = file(dir, "Ferry.apk", now - 5 * 60_000)
        val edge = file(dir, "edge.apk", now - StaleFiles.KEEP_MS + 60_000)
        val old = file(dir, "old.apk", now - StaleFiles.KEEP_MS - 60_000)
        val ancient = file(dir, "ancient.apk.part", now - 30L * 24 * 3600 * 1000)
        val future = file(dir, "future.apk", now + 3 * StaleFiles.KEEP_MS) // the clock was set back: not kept for ever
        assertEquals(3, StaleFiles.deleteOld(dir, now))
        assertTrue(fresh.exists())
        assertTrue(edge.exists())
        assertFalse(old.exists())
        assertFalse(ancient.exists())
        assertFalse(future.exists())
    }

    @Test
    fun missingFolderIsNothingToDo() {
        assertEquals(0, StaleFiles.deleteOld(File(tmp.root, "nope"), 1_800_000_000_000L))
    }
}
