package app.ferry

import android.app.Instrumentation
import android.content.Intent
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ferry.install.ApkInstaller
import app.ferry.net.ConnectionState
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

/**
 * TV suite class 5 (plan §4.14) [T4]: D-pad to the Apps row, OK on `Test.apk` → progress dialog → the APK is
 * downloaded into `files/apk/<id>.apk` → (if Ferry may not install apps yet) "Allow Ferry to install apps." →
 * **Open Settings** → `MANAGE_UNKNOWN_APP_SOURCES` for `package:app.ferry` (caught); the test allows it (`appops`),
 * goes Home and back, and Ferry opens the installer by itself: `ACTION_VIEW` of the FileProvider URI with the APK
 * MIME type and a read grant; the bytes behind it equal the PC's file. When the installer returns, `files/apk` is
 * emptied. Both system screens are caught and blocked by an `ActivityMonitor` (as Ph7's `UpdateBannerTest`, B64).
 */
@RunWith(AndroidJUnit4::class)
class TvInstallTest {
    private val ctx get() = Probe.context

    /** Records (and blocks) the two system screens; reads the installer's URI at once, as the installer would. */
    private class Recorder : Instrumentation.ActivityMonitor() {
        val intents: MutableList<Intent> = Collections.synchronizedList(mutableListOf())
        @Volatile var bytesSha: String? = null
        @Volatile var bytesLen = -1L
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            val a = intent.action
            if (a != Intent.ACTION_VIEW && a != Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) return null
            intents += Intent(intent)
            if (a == Intent.ACTION_VIEW && intent.data != null) {
                runCatching {
                    val b = Probe.context.contentResolver.openInputStream(intent.data!!)!!.use { it.readBytes() }
                    bytesLen = b.size.toLong()
                    bytesSha = Probe.sha256(b)
                }.onFailure { Probe.record("installer_uri_read_failed", it) }
            }
            return Instrumentation.ActivityResult(0, null)
        }
    }

    @Test
    fun apkFromTheOutboxIsDownloadedAndHandedToTheInstaller() {
        LaunchHelpers.cleanPrefs(welcomeDone = false)
        val rec = Recorder()
        Probe.instrumentation.addMonitor(rec)
        try {
            LaunchHelpers.launch().use { sc ->
                SendHelpers.waitConnected()
                val t = TvHelpers(sc)
                SendHelpers.waitUntil("Test.apk listed", 30_000) { t.vm.outbox.value?.files?.any { it.name == "Test.apk" } == true }
                val apk = t.vm.outbox.value!!.files.first { it.name == "Test.apk" }
                val api = (AppGraph.connection.state.value as ConnectionState.Connected).api
                val pcSha = Probe.http.newCall(Request.Builder().url(api.absoluteUrl(apk.url)).build()).execute().use {
                    Probe.sha256(it.body!!.bytes())
                }
                Probe.record("outbox_apk", "id=${apk.id} size=${apk.size} sha=$pcSha")

                t.waitFocus("first card focused", 20_000) { t.current != null }
                var downs = 0
                while (t.current?.row != "apps") {
                    check(downs++ < 4) { "no Apps row below ${t.current}" }
                    val row = t.current?.row
                    t.press("down") { it != null && it.row != row }
                }
                assertEquals("Test.apk", t.title)
                SendHelpers.device.pressDPadCenter()
                SendHelpers.waitUntil("install dialog", 10_000) { t.vm.installing.value != null }
                runCatching {
                    SendHelpers.waitText(apk.name, 5_000)
                    Shots.take("tv-install-dialog")
                }
                val target = File(File(ctx.filesDir, "apk"), "${apk.id}.apk")
                SendHelpers.waitUntil("APK downloaded", 120_000) {
                    t.vm.installing.value == null || rec.intents.isNotEmpty()
                }
                Probe.record("downloaded_file", "${target.path} exists=${target.exists()} len=${target.length()}")

                val allowedAtFirst = ApkInstaller.canInstall(ctx)
                Probe.record("can_install_at_first", allowedAtFirst)
                if (!allowedAtFirst) {
                    SendHelpers.waitText(ctx.getString(R.string.install_allow))
                    SendHelpers.settle()
                    Shots.take("tv-install-allow")
                    SendHelpers.tap(ctx.getString(R.string.open_settings))
                    SendHelpers.waitUntil("unknown-sources screen", 10_000) {
                        rec.intents.any { it.action == Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES }
                    }
                    val s = rec.intents.first { it.action == Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES }
                    Probe.record("settings_intent", s.data)
                    assertEquals("package:app.ferry", s.data.toString())
                    Probe.record("appops", SendHelpers.shell("appops set app.ferry REQUEST_INSTALL_PACKAGES allow").trim())
                    SendHelpers.waitUntil("install allowed", 10_000) { ApkInstaller.canInstall(ctx) }
                    SendHelpers.device.pressHome()
                    Thread.sleep(1_500)
                    ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                SendHelpers.waitUntil("installer opened", 20_000) { rec.intents.any { it.action == Intent.ACTION_VIEW } }
                val v = rec.intents.first { it.action == Intent.ACTION_VIEW }
                Probe.record("install_intent", "${v.data} ${v.type} flags=0x${Integer.toHexString(v.flags)}")
                Probe.record("installer_bytes", "len=${rec.bytesLen} sha=${rec.bytesSha}")
                assertEquals(ApkInstaller.APK_MIME, v.type)
                assertEquals("content://${ApkInstaller.AUTHORITY}/apk/${apk.id}.apk", v.data.toString())
                assertTrue(v.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertEquals(apk.size, rec.bytesLen)
                assertEquals(pcSha, rec.bytesSha)

                // The installer "returned" (the blocked start pauses and resumes the activity): files/apk is emptied.
                SendHelpers.waitUntil("files/apk emptied", 15_000) { SendHelpers.logged("files/apk emptied") }
                val left = File(ctx.filesDir, "apk").listFiles()?.map { it.name }.orEmpty()
                Probe.record("apk_folder_after_installer", left)
                assertTrue("files/apk empty after the installer: $left", left.isEmpty())
                SendHelpers.settle()
                Shots.take("tv-install-after-installer")
            }
        } finally {
            Probe.instrumentation.removeMonitor(rec)
        }
    }
}
