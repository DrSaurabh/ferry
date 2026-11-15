package app.ferry.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** FGS mediaPlayback (plan §4.4); casting arrives in Ph9. Ph4 only declares it (manifest checks); nothing starts it yet. */
class CastService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
