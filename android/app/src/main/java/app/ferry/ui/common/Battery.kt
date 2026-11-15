package app.ferry.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import app.ferry.AppGraph

/** Android's battery-optimisation setting (design §4.2, P10) [H23] [A8]. */
object Battery {
    fun isIgnoringOptimizations(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    /** The system "Allow Ferry to run in the background?" dialog; falls back to the battery settings list. */
    fun request(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            AppGraph.log("battery", "request dialog failed: $e")
            try {
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: Exception) {
                AppGraph.log("battery", "battery settings failed: $e2")
            }
        }
    }
}
