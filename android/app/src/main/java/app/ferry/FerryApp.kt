package app.ferry

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle

/**
 * Creates [AppGraph] and the notification channels (plan §4.2, §4.5; channels only on API 26+ [A4]). 2.1: the
 * process goes to the foreground/background (`app.fg` / `app.bg`) by a started-activities counter (no lifecycle
 * library, plan §1.3); at the background the log state is saved and the log shipped (CP109, CP137).
 */
class FerryApp : Application() {
    private var started = 0
    /** The last activity stop was a configuration change (rotation): its restart is not a new `app.fg`. */
    private var changing = false

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_TRANSFERS, getString(R.string.channel_transfers), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_RESULTS, getString(R.string.channel_results), NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(CH_CASTING, getString(R.string.channel_casting), NotificationManager.IMPORTANCE_LOW))
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                if (started++ == 0 && !changing) AppGraph.event("app.fg")
                changing = false
            }

            override fun onActivityStopped(a: Activity) {
                changing = a.isChangingConfigurations
                if (--started == 0 && !changing) {
                    AppGraph.event("app.bg")
                    AppGraph.events.saveState()
                    AppGraph.shipper.onBackground()
                }
                if (started < 0) started = 0
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityResumed(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
    }

    companion object {
        const val CH_TRANSFERS = "transfers"
        const val CH_RESULTS = "results"
        const val CH_CASTING = "casting"
    }
}
