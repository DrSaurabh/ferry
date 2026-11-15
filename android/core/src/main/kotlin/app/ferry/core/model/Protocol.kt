package app.ferry.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire models of the PC protocol (design §2.3). Field names follow ferry.py's JSON exactly.

/** `GET /api/v1/info`. v1 PCs send no `protocol`: it then reads as 1 (→ OldPc [B15]). */
@Serializable
data class PcInfo(
    val app: String = "",
    val protocol: Int = 1,
    val version: String = "",
    val name: String = "",
    @SerialName("device_type") val deviceType: String = "pc",
    val os: String? = null,
    val capabilities: List<String> = emptyList(),
    @SerialName("free_bytes") val freeBytes: Long? = null,
    @SerialName("app_version_code") val appVersionCode: Long? = null,
)

/** One entry of `POST /api/v1/check-batch`; `mtime` (ms) is omitted when unknown [B17]. */
@Serializable
data class CheckFile(
    val name: String,
    val size: Long,
    val mtime: Long? = null,
)

@Serializable
data class CheckBatchRequest(val files: List<CheckFile>)

@Serializable
data class CheckResult(val duplicate: String? = null)

@Serializable
data class CheckBatchReply(
    val ok: Boolean = false,
    val results: List<CheckResult> = emptyList(),
    @SerialName("needed_bytes") val neededBytes: Long = 0,
    @SerialName("free_bytes") val freeBytes: Long? = null,
)

/** `POST /api/v1/upload` 200 reply: result = saved | renamed | skipped. */
@Serializable
data class UploadReply(
    val result: String,
    @SerialName("saved_as") val savedAs: String = "",
    val bytes: Long = 0,
)

/** Every error reply: `{"result":"error","message":…}` (+ `reason` for 507 disk_full). */
@Serializable
data class ErrorReply(
    val result: String = "error",
    val message: String? = null,
    val reason: String? = null,
)

@Serializable
data class OutFile(
    val id: Int,
    val name: String,
    val size: Long,
    val mtime: Long? = null,
    val kind: String = "other",
    val url: String,
)

@Serializable
data class OutboxList(
    val files: List<OutFile> = emptyList(),
    @SerialName("total_size") val totalSize: Long = 0,
    @SerialName("folders_ignored") val foldersIgnored: Int = 0,
)

/** `GET /api/v1/app` (the APK bundled with the PC program, C3). */
@Serializable
data class AppInfo(
    @SerialName("version_code") val versionCode: Long,
    @SerialName("version_name") val versionName: String,
    val size: Long,
    val sha256: String,
    val url: String,
)
