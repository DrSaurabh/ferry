package app.ferry.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** FGS dataSync (plan §4.4); the upload/download work arrives in Ph5a/Ph7. Ph4 only declares it (manifest checks); nothing starts it yet. */
class TransferService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
