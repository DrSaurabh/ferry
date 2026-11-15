package app.ferry.core.scan

import org.junit.Assert.assertTrue
import org.junit.Test

/** `:core` stays pure JVM: no android.* in compiled classes (plan §0.1, §3.7). */
class NoAndroidImportsTest {
    @Test
    fun noAndroidReferences() {
        val bad = ClassScan.coreClasses().flatMap { (path, scan) ->
            (scan.classes.filter { it.startsWith("android/") || it.contains("Landroid/") } +
                scan.utf8.filter { it.startsWith("android/") || it.contains("Landroid/") })
                .distinct().map { "$path: $it" }
        }
        assertTrue("android references in :core:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
