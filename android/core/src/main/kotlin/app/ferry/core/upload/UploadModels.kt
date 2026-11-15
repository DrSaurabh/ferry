package app.ferry.core.upload

// Types of the upload engine (plan §3.2, design §4.4). The engine itself comes in Ph2b.

interface UploadSource {
    val displayName: String

    /**
     * Throws FerryException.SourceUnreadable; a source built for the original (requireOriginal) throws
     * OriginalUnreadable and NEVER falls back to the redacted stream [PB5].
     */
    fun open(): OpenedSource

    /** Re-reads size + date once after "file changed" [B5]. */
    fun refresh(): SourceMeta
}

data class SourceMeta(val size: Long, val mtimeMs: Long?)

interface OpenedSource : java.io.Closeable {
    /** -1 = unknown */
    val length: Long
    val stream: java.io.InputStream
}

data class UploadItem(
    val id: Long,
    val source: UploadSource,
    val name: String,
    val size: Long,
    val mtimeMs: Long?,
    val original: Boolean = true,
    val skipPrecheck: Boolean = false,
)

sealed interface ItemStatus {
    object Waiting : ItemStatus
    object Checking : ItemStatus
    data class Sending(val sent: Long, val total: Long) : ItemStatus
    /** renamed = "Saved as x (1).jpg" */
    data class Saved(val savedAs: String, val renamed: Boolean) : ItemStatus
    data class AlreadyOnPc(val existing: String) : ItemStatus
    data class Failed(val reason: FailReason, val detail: String? = null) : ItemStatus
    object Cancelled : ItemStatus
    data class NotSent(val reason: StopReason) : ItemStatus
}

enum class FailReason { ConnectionKeptDropping, FileChanged, CantRead, CantReadOriginal /* [PB5] */, PcError, BadRequest }

sealed interface StopReason {
    /** "Your PC needs 2.1 GB more free space" */
    data class DiskFull(val missingBytes: Long?) : StopReason
    /** 30 min without the PC [B20] */
    object PcNotFound : StopReason
    /** dataSync onTimeout [A6] */
    object TimeLimit : StopReason
    /** [B15] */
    object OldPc : StopReason
    object Cancelled : StopReason
}

enum class Phase { Idle, Checking, Sending, WaitingForPc, Finished }

data class ItemRow(val id: Long, val name: String, val size: Long, val original: Boolean, val status: ItemStatus)

data class EngineState(
    val phase: Phase,
    val items: List<ItemRow>,
    val sentBytes: Long,
    val totalBytes: Long,
    val speedBps: Long,
    val etaSeconds: Long?,
    val pcName: String?,
    val summary: Summary?,
)

data class Summary(
    val saved: Int,
    val renamed: Int,
    val alreadyOnPc: Int,
    val savedWithoutLocation: Int,
    val failed: Int,
    val notSent: Int,
    val cancelled: Int,
    val stop: StopReason?,
)

data class EngineConfig(
    val workers: Int = 2,
    val bigFileBytes: Long = 256L shl 20,
    val checkChunk: Int = 500,
    val probeEveryMs: Long = 3_000,
    val rediscoverEveryMs: Long = 30_000,
    val waitLimitMs: Long = 30 * 60_000L,
    val maxNetFailures: Int = 3,
    val cancelGraceMs: Long = 10_000,
    val progressEveryMs: Long = 250,
    val speedWindowMs: Long = 3_000,
    val diskMargin: Long = 64L shl 20,
)
