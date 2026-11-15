package app.ferry.core.update

import app.ferry.core.download.FileSink
import app.ferry.core.model.AppInfo
import app.ferry.core.net.FerryApi
import app.ferry.core.net.FerryException
import app.ferry.core.util.Hex
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

/** App update through the PC (design §4.6, C3). */
object AppUpdate {
    /** True when the PC carries a newer build than the installed one. */
    fun isNewer(info: AppInfo?, installedVersionCode: Long): Boolean =
        info != null && info.versionCode > installedVersionCode

    /**
     * Downloads the APK to `target.part`, checks size == [AppInfo.size] and sha256 == [AppInfo.sha256], then
     * renames it onto [target]. On any mismatch or failure the part file is deleted and the exception is thrown
     * (`BadReply` for a size/hash mismatch); [target] never holds an unverified APK (an older one is deleted first).
     */
    suspend fun downloadApk(api: FerryApi, info: AppInfo, target: File, onProgress: (Long) -> Unit): File {
        target.delete()
        val sink = VerifyingSink(target, info)
        api.download(info.url, info.size, sink, onProgress)
        return target
    }

    private class VerifyingSink(target: File, private val info: AppInfo) : FileSink(target) {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var count = 0L

        override fun open(): OutputStream {
            val o = super.open()
            return object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    o.write(b, off, len)
                    digest.update(b, off, len)
                    count += len
                }
                override fun flush() = o.flush()
                override fun close() = o.close()
            }
        }

        override fun commit(): String {
            if (count != info.size || part.length() != info.size) {
                throw FerryException.BadReply("APK size ${part.length()}, expected ${info.size}")
            }
            val sha = Hex.encode(digest.digest())
            if (!sha.equals(info.sha256, ignoreCase = true)) {
                throw FerryException.BadReply("APK sha256 $sha, expected ${info.sha256}")
            }
            return super.commit()
        }
    }
}
