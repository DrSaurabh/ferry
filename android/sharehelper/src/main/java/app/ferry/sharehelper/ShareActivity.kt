package app.ferry.sharehelper

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Writes its three files (if missing) into `files/share/` and shares them to Ferry's ShareReceiverActivity with
 * `ACTION_SEND_MULTIPLE`, the URIs in `EXTRA_STREAM` and `ClipData`, and `FLAG_GRANT_READ_URI_PERMISSION`; then
 * finishes (extra `prepareOnly`: write the files and finish). File contents follow [pattern] (android/ci/verify_inbox.py checks the PC copies with the same rule).
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = File(filesDir, "share").apply { mkdirs() }
        val uris = ArrayList<Uri>()
        for ((index, name, size) in FILES) {
            val f = File(dir, name)
            if (f.length() != size) f.outputStream().buffered(1 shl 16).use { out -> pattern(index, size) { out.write(it) } }
            uris += FileProvider.getUriForFile(this, "app.ferry.sharehelper.files", f)
        }
        if (intent.getBooleanExtra("prepareOnly", false)) { // files only: the test's control runs first
            Log.i("FerryShareHelper", "files ready: $uris")
            finish()
            return
        }
        val clip = ClipData.newRawUri("files", uris[0])
        for (u in uris.drop(1)) clip.addItem(ClipData.Item(u))
        val send = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setClassName("app.ferry", "app.ferry.share.ShareReceiverActivity")
            .setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = clip
        Log.i("FerryShareHelper", "sharing $uris")
        startActivity(send)
        finish()
    }

    companion object {
        /** index, name, size: the 30 MB file first, so it is sending while the share activity is long gone. */
        val FILES = listOf(
            Triple(1, "ferry-share-big.bin", 30L shl 20),
            Triple(2, "ferry-share-notes.txt", 20_000L),
            Triple(3, "ferry-share-small.bin", 300_000L),
        )

        /**
         * Block b (4096 bytes) of file k: 8-byte big-endian `(k shl 56) or b`, then byte j = (j·31 + k·7 + b) mod 256
         * for j = 8…4095; the last block is cut to the size.
         */
        fun pattern(k: Int, size: Long, write: (ByteArray) -> Unit) {
            var b = 0L
            var left = size
            val blk = ByteArray(4096)
            while (left > 0) {
                val head = (k.toLong() shl 56) or b
                for (i in 0 until 8) blk[i] = (head ushr (56 - 8 * i)).toByte()
                for (j in 8 until 4096) blk[j] = ((j * 31 + k * 7 + b) and 0xFF).toByte()
                val n = minOf(4096L, left).toInt()
                write(if (n == 4096) blk else blk.copyOf(n))
                left -= n
                b++
            }
        }
    }
}
