package app.ferry.data

import android.content.Context
import app.ferry.player.ResumeRule

/**
 * Video positions for "Resume from 12:34" (design §5.2, plan §1.3): a separate prefs file keyed `name|size|mtime`,
 * capped at [ResumeRule.MAX_ENTRIES] (the oldest saves are dropped). Main thread or any thread; `apply()` writes.
 */
class ResumeStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("resume", Context.MODE_PRIVATE)

    fun get(key: String): ResumeRule.Entry? = ResumeRule.decode(sp.getString(key, null))

    @Synchronized
    fun save(key: String, posMs: Long, durMs: Long, nowMs: Long = System.currentTimeMillis()) {
        val saved = sp.all.mapNotNull { (k, v) -> ResumeRule.decode(v as? String)?.let { k to it.savedAtMs } }.toMap()
        val e = sp.edit()
        for (k in ResumeRule.evict(saved, key)) e.remove(k)
        e.putString(key, ResumeRule.encode(ResumeRule.Entry(posMs.coerceAtLeast(0), durMs, nowMs))).apply()
    }

    fun remove(key: String) {
        sp.edit().remove(key).apply()
    }

    /** Tests start from a clean state. */
    fun clear() {
        sp.edit().clear().commit()
    }
}
