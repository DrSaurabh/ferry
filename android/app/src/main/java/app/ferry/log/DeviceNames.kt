package app.ferry.log

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.Locale

/**
 * The name this phone/TV shows on the PC and in the TV state (design §10, M23, M25-C15; plan §1.3):
 * - phone: the user's own name (Settings, H76) → `Settings.Global.DEVICE_NAME` → "Manufacturer Model";
 * - TV: `DEVICE_NAME` → "Fire TV <install id>".
 * Resolved at [refresh] (start, Settings rename, activity start), so requests do not read Settings each time.
 * Each new result is logged once as `device_name name= src=`.
 */
class DeviceNames(
    private val ctx: Context,
    private val isTv: () -> Boolean,
    private val override: () -> String?,
    private val installId: () -> String,
    private val logEvent: (name: String, src: String) -> Unit,
) {
    @Volatile private var current: Pair<String, String>? = null

    val name: String get() = (current ?: refresh()).first

    /** Reads the chain again; logs `device_name` when the name or its source changed. */
    fun refresh(): Pair<String, String> {
        val global = try {
            Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (e: Exception) {
            null
        }
        val r = choose(isTv(), override(), global, Build.MANUFACTURER, Build.MODEL, installId())
        val changed = synchronized(this) {
            val c = r != current
            current = r
            c
        }
        if (changed) logEvent(r.first, r.second)
        return r
    }

    companion object {
        /** The chain itself (JVM-tested in `DeviceNamesTest`): name and its source `override|system|model|fallback`. */
        fun choose(tv: Boolean, override: String?, global: String?, manufacturer: String?, model: String?, installId: String): Pair<String, String> {
            if (!tv && !override.isNullOrBlank()) return override.trim() to "override"
            if (!global.isNullOrBlank()) return global.trim() to "system"
            if (tv) return "Fire TV $installId" to "fallback"
            val maker = manufacturer?.trim().orEmpty()
            val m = model?.trim().orEmpty()
            val full = when {
                m.isEmpty() -> maker
                maker.isEmpty() || m.lowercase(Locale.US).startsWith(maker.lowercase(Locale.US)) -> m
                else -> "$maker $m"
            }
            return if (full.isEmpty()) "Android $installId" to "fallback" else full to "model"
        }
    }
}
