package app.ferry.data

import android.content.Context
import app.ferry.core.HostPort
import app.ferry.core.discovery.PcStore

/**
 * Small settings in SharedPreferences (plan §1.3, design §3.5, §4.2). Implements `:core`'s [PcStore] for the
 * `PcLocator`. Addresses are stored as "host:port" so the port survives even when it is the default.
 */
class Prefs(ctx: Context) : PcStore {
    private val sp = ctx.getSharedPreferences("ferry", Context.MODE_PRIVATE)

    override var lastKnown: HostPort?
        get() = host(K_LAST)
        set(v) = putHost(K_LAST, v)

    override var manual: HostPort?
        get() = host(K_MANUAL)
        set(v) = putHost(K_MANUAL, v)

    override var chosenName: String?
        get() = sp.getString(K_CHOSEN, null)
        set(v) = sp.edit().putString(K_CHOSEN, v).apply()

    /** The TV used last for Play on TV [A24] (Ph9). */
    var lastTv: HostPort?
        get() = host(K_LAST_TV)
        set(v) = putHost(K_LAST_TV, v)

    /** That TV's name (shown before it has answered again). */
    var lastTvName: String?
        get() = sp.getString(K_LAST_TV_NAME, null)
        set(v) = sp.edit().putString(K_LAST_TV_NAME, v).apply()

    var slideshowSeconds: Int
        get() = sp.getInt(K_SLIDESHOW, 5)
        set(v) = sp.edit().putInt(K_SLIDESHOW, v).apply()

    var welcomeDone: Boolean
        get() = sp.getBoolean(K_WELCOME, false)
        set(v) = sp.edit().putBoolean(K_WELCOME, v).apply()

    /** Files still unsent when the process died (interrupted-transfer banner [A8], Ph5a). */
    var interruptedCount: Int
        get() = sp.getInt(K_INTERRUPTED, 0)
        set(v) = sp.edit().putInt(K_INTERRUPTED, v).apply()

    /** POST_NOTIFICATIONS asked once, when the first transfer starts [H23]. */
    var notificationsAsked: Boolean
        get() = sp.getBoolean(K_NOTIF, false)
        set(v) = sp.edit().putBoolean(K_NOTIF, v).apply()

    /** The media permission request was refused once; a second refusal leads to Settings (design §4.2). */
    var mediaDenied: Boolean
        get() = sp.getBoolean(K_MEDIA_DENIED, false)
        set(v) = sp.edit().putBoolean(K_MEDIA_DENIED, v).apply()

    /** Tests start from a clean state (plan §4.14: never `pm clear`, which would drop granted permissions). */
    fun clear() {
        sp.edit().clear().commit()
    }

    private fun host(key: String): HostPort? = sp.getString(key, null)?.let { HostPort.parse(it) }

    private fun putHost(key: String, v: HostPort?) {
        sp.edit().putString(key, v?.let { "${it.host}:${it.port}" }).apply()
    }

    private companion object {
        const val K_LAST = "last_pc"
        const val K_MANUAL = "manual_pc"
        const val K_CHOSEN = "chosen_pc_name"
        const val K_LAST_TV = "last_tv"
        const val K_LAST_TV_NAME = "last_tv_name"
        const val K_SLIDESHOW = "slideshow_seconds"
        const val K_WELCOME = "welcome_done"
        const val K_INTERRUPTED = "interrupted_count"
        const val K_NOTIF = "notifications_asked"
        const val K_MEDIA_DENIED = "media_denied"
    }
}
