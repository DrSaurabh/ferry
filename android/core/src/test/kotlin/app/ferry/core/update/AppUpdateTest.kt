package app.ferry.core.update

import app.ferry.core.download.FakeDownloadApi
import app.ferry.core.model.AppInfo
import app.ferry.core.net.FerryException
import app.ferry.core.sha256
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/** `AppUpdate` (plan §3.7, design §4.6). */
class AppUpdateTest {
    private val dir = Files.createTempDirectory("appupdate").toFile()
    private val target = File(dir, "update/Ferry.apk")
    private val part = File(target.path + ".part")
    private val apk = Random(3).nextBytes(200_000)
    private val good = AppInfo(versionCode = 20057, versionName = "2.0.57", size = apk.size.toLong(), sha256 = sha256(apk), url = "/app/Ferry.apk")

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun isNewerCases() {
        assertFalse(AppUpdate.isNewer(null, 20050))
        assertFalse(AppUpdate.isNewer(good, 20057)) // equal
        assertFalse(AppUpdate.isNewer(good, 20060)) // PC has an older one
        assertTrue(AppUpdate.isNewer(good, 20056))
        assertTrue(AppUpdate.isNewer(good, 1)) // a dev build (versionCode 1, CP13) is always older
    }

    @Test
    fun verifiedDownload() = runTest {
        target.parentFile.mkdirs()
        target.writeBytes(byteArrayOf(1, 2, 3)) // an older download is replaced
        val got = AppUpdate.downloadApk(FakeDownloadApi { apk }, good, target) {}
        assertArrayEquals(apk, got.readBytes())
        assertFalse(part.exists())
    }

    @Test
    fun shaMismatchDeletesTheFile() = runTest {
        target.parentFile.mkdirs()
        target.writeBytes(byteArrayOf(1, 2, 3))
        val bad = good.copy(sha256 = sha256(apk + byteArrayOf(0)))
        try {
            AppUpdate.downloadApk(FakeDownloadApi { apk }, bad, target) {}
            fail("expected BadReply")
        } catch (e: FerryException.BadReply) {
            assertTrue(e.message!!, e.message!!.contains("sha256"))
        }
        assertFalse(part.exists())
        assertFalse(target.exists())
    }

    @Test
    fun sizeMismatchDeletesTheFile() = runTest {
        val bigger = good.copy(size = apk.size + 5L)
        val api = FakeDownloadApi { apk }
        api.plans += FakeDownloadApi.Plan(contentLength = bigger.size) // the PC announces 5 more bytes than it sends
        try {
            AppUpdate.downloadApk(api, bigger, target) {}
            fail("expected BadReply")
        } catch (e: FerryException.BadReply) {
            // expected
        }
        assertFalse(part.exists())
        assertFalse(target.exists())
    }
}
