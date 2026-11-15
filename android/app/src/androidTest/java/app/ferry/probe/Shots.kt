package app.ferry.probe

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/**
 * Screenshot helper [CP16] [PA16]: PNGs in <baseDir>/screenshots/<suite>/. baseDir is
 * getExternalFilesDir(null) (host: `adb pull /sdcard/Android/data/app.ferry/files/screenshots`) or, for
 * suites run with `-e store internal`, filesDir (host: `adb exec-out run-as app.ferry tar`).
 */
object Shots {
    fun take(name: String): File {
        val suite = Probe.arg("suite", "probe")
        val dir = File(Probe.baseDir, "screenshots/$suite").apply { mkdirs() }
        val bmp: Bitmap = checkNotNull(Probe.instrumentation.uiAutomation.takeScreenshot()) { "no screenshot" }
        val f = File(dir, "$name.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }
}
