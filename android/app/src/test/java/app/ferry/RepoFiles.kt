package app.ferry

import java.io.File

/** The repository root, found from the test's working directory (Gradle runs app tests in `android/app`). */
object RepoFiles {
    val root: File by lazy {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "docs/v2.1-log-events.md").isFile) d = d.parentFile
        d ?: error("repo root (with docs/v2.1-log-events.md) not found from ${System.getProperty("user.dir")}")
    }

    fun kotlinFiles(dir: String): List<File> =
        File(root, dir).walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.sortedBy { it.path }.toList()
}
