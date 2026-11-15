package app.ferry.core.net

/** Error model of `:core` (plan §3.4). The UI never shows these messages; `:app` maps each case to a string. */
sealed class FerryException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause) {
    /** connect refused / no route / no network. */
    class Unreachable(cause: Throwable) : FerryException("PC not reachable", cause)
    class ConnectionLost(val bodyFullySent: Boolean, val bytesSent: Long, cause: Throwable) :
        FerryException("connection lost", cause)
    /** phase = connect | write | response. */
    class Timeout(val phase: String, cause: Throwable) : FerryException("timed out ($phase)", cause)
    class Http(val code: Int, val serverMessage: String?) : FerryException("HTTP $code")
    /** 507 */
    class DiskFull : FerryException("PC disk is full")
    class BadReply(detail: String) : FerryException("unexpected reply: $detail")
    /** [B15] */
    class OldPc(val protocol: Int) : FerryException("older Ferry on the PC")
    class SourceChanged : FerryException("file changed while sending")
    class SourceUnreadable(cause: Throwable?) : FerryException("can't read the file", cause)
    /** [PB5] */
    class OriginalUnreadable(cause: Throwable?) : FerryException("can't read the original (location)", cause)
    class NotEnoughSpace(val needed: Long, val free: Long) : FerryException("not enough space on the phone")
}
