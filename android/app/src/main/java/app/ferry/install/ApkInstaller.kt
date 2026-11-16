package app.ferry.install

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.ferry.AppGraph
import java.io.File

/**
 * Hands an APK to the system installer (design §4.6, §5.4, plan §4.8) [A14] [H21]: `ACTION_VIEW` with the APK MIME
 * type and a read grant; one path for API 25–35, used for the app update and for APKs from the PC's outbox. No
 * `PackageInstaller` session; the user confirms in the system dialog.
 */
object ApkInstaller {
    const val APK_MIME = "application/vnd.android.package-archive"
    const val AUTHORITY = "app.ferry.files"

    /**
     * May Ferry start an install? API 26+: `canRequestPackageInstalls()`. API 25 (Fire OS 6) has one global
     * "Unknown sources" switch instead, read from `Settings.Secure` (if it can't be read, the installer itself says so).
     */
    fun canInstall(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 26) ctx.packageManager.canRequestPackageInstalls()
        else legacyUnknownSourcesOn(ctx)

    @Suppress("DEPRECATION")
    private fun legacyUnknownSourcesOn(ctx: Context): Boolean = try {
        Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 1) != 0
    } catch (e: Exception) {
        true
    }

    /**
     * Opens "Install unknown apps" for Ferry (API 26+), inside try/catch: false when the device has no such screen
     * (some Fire OS builds) or on API 25, so the caller shows the steps as text instead [A14].
     */
    fun openUnknownSourcesSettings(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        return try {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (e: ActivityNotFoundException) {
            AppGraph.log("install", "no unknown-sources screen: $e")
            false
        } catch (e: SecurityException) {
            AppGraph.log("install", "unknown-sources screen refused: $e")
            false
        }
    }

    /** A file in Ferry's own folders (`files/update`, `files/apk`) as a FileProvider URI. */
    fun uriFor(ctx: Context, file: File): Uri = FileProvider.getUriForFile(ctx, AUTHORITY, file)

    /** Starts the system installer; false if no installer answered. */
    fun install(ctx: Context, apk: Uri): Boolean = try {
        AppGraph.log("install", "installer for $apk")
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(apk, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: ActivityNotFoundException) {
        AppGraph.log("install", "no installer: $e")
        false
    }
}
