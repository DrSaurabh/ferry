package app.ferry.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.FileNotFoundException

/**
 * Player retry rule (plan §4.10 [PA18], design §5.2 [B13]): up to 8 retries, 1 s apart, so playback
 * reconnects after the PC drops an idle connection (60 s); give up at once on errors a retry can't fix.
 * Both the retry count (constructor) and the delay are set: with only one, the player gives up after
 * the default 3 tries or never stops.
 */
@OptIn(UnstableApi::class)
object FerryLoadErrorPolicy : DefaultLoadErrorHandlingPolicy(MAX_RETRIES) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val e = loadErrorInfo.exception
        val fatal = e is ParserException ||
            e is FileNotFoundException ||
            (e is HttpDataSource.InvalidResponseCodeException && e.responseCode in 400..499) ||
            loadErrorInfo.errorCount > MAX_RETRIES
        return if (fatal) C.TIME_UNSET else RETRY_DELAY_MS
    }
}

private const val MAX_RETRIES = 8
private const val RETRY_DELAY_MS = 1_000L
