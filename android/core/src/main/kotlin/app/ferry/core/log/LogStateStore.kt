package app.ferry.core.log

import app.ferry.core.FerryJson
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException

/** What survives a restart of the event log (plan CP109, M8): the last written `seq`, the ship cursor, the session. */
@Serializable
data class LogState(
    val seq: Long = 0,
    val shippedSeq: Long = 0,
    val session: String = "",
)

/**
 * `files/ferry-log-state.json` (plan §1.2, CP109): written atomically (tmp + rename). A missing or broken file reads
 * as an empty state; write errors are swallowed (logging must never break the app; `EventLog` also takes the
 * last `#seq` from the log file at start, CP133).
 */
class LogStateStore(private val file: File) {
    fun load(): LogState = try {
        if (file.isFile) FerryJson.decodeFromString(LogState.serializer(), file.readText(Charsets.UTF_8)) else LogState()
    } catch (e: Exception) {
        LogState()
    }

    fun save(state: LogState) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(FerryJson.encodeToString(LogState.serializer(), state), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: IOException) {
            // ignore: the next save tries again
        }
    }
}
