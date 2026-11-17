package app.ferry.data

import java.io.File

/**
 * Start-up cleanup of Ferry's own download folders (`files/apk`, `files/update`) by age (Stage 7 audit S5). Android
 * may stop Ferry's process while its installer screen is open and something else (a share, a notification) starts
 * Ferry again: a file handed to the installer a moment ago must not be deleted under it, so only files older than
 * [KEEP_MS] go. Younger leftovers are deleted by a later start, or replaced by the next download.
 */
object StaleFiles {
    const val KEEP_MS = 60L * 60 * 1000

    /** Deletes the files of [dir] last written more than [keepMs] before [nowMs] (or dated in the future); how many. */
    fun deleteOld(dir: File, nowMs: Long, keepMs: Long = KEEP_MS): Int {
        var n = 0
        for (f in dir.listFiles() ?: return 0) {
            val age = nowMs - f.lastModified()
            if ((age >= keepMs || age < -keepMs) && f.delete()) n++
        }
        return n
    }
}
