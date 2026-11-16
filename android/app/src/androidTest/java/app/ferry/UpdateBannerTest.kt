package app.ferry

import android.app.Instrumentation
import android.content.Intent
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ferry.core.HostPort
import app.ferry.install.ApkInstaller
import app.ferry.install.UpdateProgress
import app.ferry.net.UpdateState
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.Collections

/**
 * Phone suite class 8 (plan §4.14) [C3]: PC-U carries this debug APK under a json with `version_code 2000000000`.
 * Home shows "Ferry … is available → Update"; tap → download into `files/update/Ferry.apk`, size + sha256 checked;
 * then, as an adb-installed app may not install apps yet, "Allow Ferry to install apps." → **Open Settings** opens
 * `MANAGE_UNKNOWN_APP_SOURCES` for `package:app.ferry`; the test allows it (`appops`), goes Home and back, and on
 * that return Ferry offers the install again by itself: `ACTION_VIEW` of the FileProvider URI with the APK MIME type
 * and a read grant. Both system screens are caught (and blocked) by an `ActivityMonitor`: an installer can't be
 * completed in CI (plan §6 Ph7 risks). No Compose test rule (B50).
 */
@RunWith(AndroidJUnit4::class)
@RequiresApi(26)
class UpdateBannerTest {
    private val ctx get() = Probe.context

    private class Recorder : Instrumentation.ActivityMonitor() {
        val intents: MutableList<Intent> = Collections.synchronizedList(mutableListOf())
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            val a = intent.action
            if (a != Intent.ACTION_VIEW && a != Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) return null
            intents += Intent(intent)
            return Instrumentation.ActivityResult(0, null)
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun updateIsDownloadedVerifiedAndHandedToTheInstaller() {
        LaunchHelpers.cleanPrefs(welcomeDone = true)
        AppGraph.prefs.manual = HostPort.parse(Probe.arg("pcU", "10.0.2.2:18767"))
        val rec = Recorder()
        Probe.instrumentation.addMonitor(rec)
        try {
            LaunchHelpers.launch().use {
                SendHelpers.waitConnected()
                SendHelpers.waitUntil("update offered", 30_000) { AppGraph.update.value is UpdateState.Available }
                val info = (AppGraph.update.value as UpdateState.Available).info
                Probe.record("update_offered", "${info.versionName} code=${info.versionCode} size=${info.size} installed=${BuildConfig.VERSION_CODE}")
                val bannerText = ctx.getString(R.string.update_available, info.versionName)
                SendHelpers.waitText(bannerText)
                SendHelpers.settle()
                Shots.take("update-banner")

                SendHelpers.tap(bannerText)
                SendHelpers.waitUntil("update downloaded", 120_000) {
                    AppGraph.updater.progress.value.let { it is UpdateProgress.Ready || it is UpdateProgress.Failed }
                }
                val ready = AppGraph.updater.progress.value
                assertTrue("update download: $ready", ready is UpdateProgress.Ready)
                val file = (ready as UpdateProgress.Ready).file
                val sha = sha256(file.readBytes())
                Probe.record("update_file", "${file.path} ${file.length()} sha=$sha")
                assertEquals(info.size, file.length())
                assertEquals(info.sha256.lowercase(), sha)

                val allowedAtFirst = ApkInstaller.canInstall(ctx)
                Probe.record("can_install_at_first", allowedAtFirst)
                if (!allowedAtFirst) {
                    SendHelpers.waitText(ctx.getString(R.string.install_allow))
                    SendHelpers.settle()
                    Shots.take("update-allow-install")
                    SendHelpers.tap(ctx.getString(R.string.open_settings))
                    SendHelpers.waitUntil("unknown-sources screen", 10_000) {
                        rec.intents.any { it.action == Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES }
                    }
                    val s = rec.intents.first { it.action == Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES }
                    Probe.record("settings_intent", s.data)
                    assertEquals("package:app.ferry", s.data.toString())
                    // What the user does in that screen; then back to Ferry, which checks again on resume.
                    Probe.record("appops", SendHelpers.shell("appops set app.ferry REQUEST_INSTALL_PACKAGES allow").trim())
                    SendHelpers.waitUntil("install allowed", 10_000) { ApkInstaller.canInstall(ctx) }
                    SendHelpers.device.pressHome()
                    Thread.sleep(1_500)
                    ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                SendHelpers.waitUntil("installer opened", 20_000) { rec.intents.any { it.action == Intent.ACTION_VIEW } }
                val v = rec.intents.first { it.action == Intent.ACTION_VIEW }
                Probe.record("install_intent", "${v.data} ${v.type} flags=0x${Integer.toHexString(v.flags)}")
                assertEquals(ApkInstaller.APK_MIME, v.type)
                assertEquals("content://${ApkInstaller.AUTHORITY}/update/Ferry.apk", v.data.toString())
                assertTrue(v.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                // What the installer would read through that URI is the verified APK.
                val viaProvider = ctx.contentResolver.openInputStream(v.data!!)!!.use { it.readBytes() }
                assertEquals(info.sha256.lowercase(), sha256(viaProvider))
                SendHelpers.settle()
                Shots.take("update-after-installer")
            }
        } finally {
            Probe.instrumentation.removeMonitor(rec)
        }
    }
}
