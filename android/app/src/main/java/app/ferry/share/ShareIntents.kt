package app.ferry.share

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * The URIs of an incoming Share (design §4.1.7, plan §6 Ph5b [A5]): `EXTRA_STREAM` (one URI for `ACTION_SEND`, a
 * list for `ACTION_SEND_MULTIPLE`, read with `IntentCompat` so API 25–35 all work) plus every URI of the
 * intent's `ClipData` (some senders put them only there). Order kept, duplicates and non-content/file URIs dropped.
 */
object ShareIntents {
    fun parse(i: Intent?): List<Uri> {
        if (i == null) return emptyList()
        val found = LinkedHashSet<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { found += it }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { found += it.filterNotNull() }
        }
        i.clipData?.let { clip ->
            for (n in 0 until clip.itemCount) clip.getItemAt(n)?.uri?.let { found += it }
        }
        return found.filter { it.scheme == ContentResolver.SCHEME_CONTENT || it.scheme == ContentResolver.SCHEME_FILE }
    }
}
