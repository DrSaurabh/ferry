package app.ferry.share

import android.app.Activity
import android.os.Bundle
import app.ferry.ui.common.comingSoon

/** Share → Ferry target (design §4.1.7). Ph4 declares it (manifest checks); the real forwarding is Ph5b. */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        comingSoon(this)
        finish()
    }
}
