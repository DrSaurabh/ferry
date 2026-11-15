package app.ferry.probe

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/**
 * Screenshot helper [CP16] [PA16]: PNGs in getExternalFilesDir(null)/screenshots/<suite>/, pulled by the
 * host with `adb pull /sdcard/Android/data/app.ferry/files/screenshots`.
 */
object Shots {
    fun take(name: String): File {
        val suite = Probe.arg("suite", "probe")
        val dir = File(Probe.context.getExternalFilesDir(null), "screenshots/$suite").apply { mkdirs() }
        val bmp: Bitmap = checkNotNull(Probe.instrumentation.uiAutomation.takeScreenshot()) { "no screenshot" }
        val f = File(dir, "$name.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }
}
