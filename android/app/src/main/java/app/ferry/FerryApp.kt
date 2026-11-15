package app.ferry

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/** Creates [AppGraph] and the notification channels (plan §4.2, §4.5; channels only on API 26+ [A4]). */
class FerryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_TRANSFERS, getString(R.string.channel_transfers), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_RESULTS, getString(R.string.channel_results), NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(CH_CASTING, getString(R.string.channel_casting), NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        const val CH_TRANSFERS = "transfers"
        const val CH_RESULTS = "results"
        const val CH_CASTING = "casting"
    }
}
