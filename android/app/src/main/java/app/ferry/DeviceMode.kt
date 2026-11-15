package app.ferry

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/** TV vs phone (plan §4.2): UI mode television or the leanback feature; debug override for emulator tests. */
object DeviceMode {
    const val EXTRA_UI = "app.ferry.debug.UI"
    const val EXTRA_NET = "app.ferry.debug.NET"
    const val EXTRA_CAST_RECEIVER = "app.ferry.debug.CAST_RECEIVER"

    fun isTv(ctx: Context, override: String?): Boolean {
        val ui = ctx.getSystemService(UiModeManager::class.java)
        val television = ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        @Suppress("DEPRECATION")
        val leanback = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        return DeviceModeLogic.isTv(television, leanback, BuildConfig.DEBUG, override)
    }
}
