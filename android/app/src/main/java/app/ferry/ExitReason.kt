package app.ferry

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/** Why the previous process ended (`app.start exit=`, M18, G6); API 30+ only, else null (field left empty). */
object ExitReason {
    fun last(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val am = ctx.getSystemService(ActivityManager::class.java) ?: return null
            val info = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull() ?: return null
            name(info.reason)
        } catch (e: Exception) {
            null
        }
    }

    private fun name(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
        ApplicationExitInfo.REASON_EXIT_SELF -> "self"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init_failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resources"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "code_$r"
    }
}
