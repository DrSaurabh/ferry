package app.ferry.core.it

import app.ferry.core.HostPort
import org.junit.Assert.fail
import org.junit.Assume
import java.io.Closeable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A real `pc/ferry.py` for integration tests (plan §3.6 [B24]): temp root with in/, out/, data/; TCP port 0,
 * discovery off or port 0; waits for `FERRY_READY port=N discovery=M`.
 */
class FerryPcProcess private constructor(
    val root: File,
    private val proc: Process,
    private val output: StringBuffer,
    val base: HostPort,
    val discoveryPort: Int?,
    private val freeBytes: Long?,
    private val appDir: File?,
) : Closeable {
    val inbox = File(root, "in")
    val outbox = File(root, "out")
    val data = File(root, "data")
    /** 2.1: the request log lives in `<data>/logs/` (CP110). */
    val log = File(data, "logs/ferry-log.txt")

    /** Everything ferry.py printed so far (attached to failures). */
    fun output(): String = output.toString()

    /**
     * [PB12] The PC log is written by a background thread: send a sentinel request (404, always logged),
     * wait until its line is in ferry-log.txt (≤ 5 s) and return the lines before it.
     */
    fun awaitLogUpTo(): List<String> {
        val marker = "/api/v1/__sentinel_" + UUID.randomUUID().toString().replace("-", "")
        val c = URL("http://${base.host}:${base.port}$marker").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5_000
            c.readTimeout = 5_000
            c.responseCode // 404
        } finally {
            c.disconnect()
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (log.exists()) {
                val lines = log.readLines(Charsets.UTF_8)
                val i = lines.indexOfFirst { it.contains(marker) }
                if (i >= 0) return lines.subList(0, i)
            }
            Thread.sleep(50)
        }
        fail("sentinel $marker never appeared in $log\n--- ferry.py output ---\n${output()}")
        throw IllegalStateException()
    }

    /** False after [restartOnSamePort]: the new process owns (and deletes) the temp root. */
    @Volatile private var ownsRoot = true

    /**
     * Kills this ferry.py (no clean shutdown) and starts a new one on the same port with the same folders
     * (plan §3.7 `pcRestartOnSamePort`, Linux only). Retries the start for 15 s.
     */
    fun restartOnSamePort(): FerryPcProcess {
        ownsRoot = false
        proc.destroyForcibly()
        proc.waitFor(5, TimeUnit.SECONDS)
        val deadline = System.currentTimeMillis() + 15_000
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            val (pc, out) = launch(root, base.port, freeBytes, discoveryPort != null, appDir, 10_000)
            if (pc != null) return pc
            last = out
            Thread.sleep(500)
        }
        fail("ferry.py did not restart on port ${base.port} within 15 s\n--- output ---\n$last")
        throw IllegalStateException()
    }

    override fun close() {
        proc.destroy()
        if (!proc.waitFor(5, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            proc.waitFor(5, TimeUnit.SECONDS)
        }
        if (!ownsRoot) return
        for (attempt in 1..3) { // Windows may hold files briefly after the process ends
            if (!root.exists() || root.deleteRecursively()) return
            Thread.sleep(200)
        }
    }

    companion object {
        val pcDir: File = File(System.getProperty("ferry.pcDir") ?: "../../pc")
        private val python: String = System.getProperty("ferry.python") ?: "python3"
        private val requirePython: Boolean = System.getProperty("ferry.requirePython") == "true"

        @Volatile private var pythonChecked: Boolean? = null

        private fun pythonAvailable(): Boolean {
            pythonChecked?.let { return it }
            val ok = try {
                val p = ProcessBuilder(python, "--version").redirectErrorStream(true).start()
                p.inputStream.readBytes()
                p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0
            } catch (e: Exception) {
                false
            }
            pythonChecked = ok
            return ok
        }

        fun start(freeBytes: Long? = null, discovery: Boolean = false, appDir: File? = null): FerryPcProcess {
            if (!pythonAvailable()) {
                if (requirePython) fail("'$python' not found, but ferry.requirePython=true (CI)")
                Assume.assumeTrue("'$python' not found: integration test skipped", false)
            }
            val root = Files.createTempDirectory("ferry-it").toFile()
            for (d in listOf("in", "out", "data")) File(root, d).mkdirs()
            val (pc, out) = launch(root, 0, freeBytes, discovery, appDir, 15_000)
            if (pc == null) {
                root.deleteRecursively()
                fail("ferry.py did not print FERRY_READY within 15 s\n--- output ---\n$out")
            }
            return pc!!
        }

        /** Starts ferry.py on [root]; null (+ its output) when it did not get ready in [timeoutMs]. */
        private fun launch(
            root: File,
            port: Int,
            freeBytes: Long?,
            discovery: Boolean,
            appDir: File?,
            timeoutMs: Long,
        ): Pair<FerryPcProcess?, String> {
            val ferry = File(pcDir, "ferry.py")
            check(ferry.isFile) { "ferry.py not found at $ferry" }
            val cmd = mutableListOf(
                python, "-u", ferry.absolutePath, "--no-open", "--port", port.toString(),
                "--discovery-port", if (discovery) "0" else "off",
                "--inbox", File(root, "in").absolutePath, "--outbox", File(root, "out").absolutePath,
                "--data-dir", File(root, "data").absolutePath,
            )
            if (freeBytes != null) cmd += listOf("--test-free-bytes", freeBytes.toString())
            if (appDir != null) cmd += listOf("--app-dir", appDir.absolutePath)
            val pb = ProcessBuilder(cmd).redirectErrorStream(true).directory(root)
            pb.environment()["PYTHONIOENCODING"] = "utf-8"
            val proc = pb.start()
            val output = StringBuffer()
            val lines = LinkedBlockingQueue<String>()
            Thread({
                try {
                    proc.inputStream.bufferedReader(Charsets.UTF_8).forEachLine {
                        output.append(it).append('\n')
                        lines.add(it)
                    }
                } catch (e: Exception) {
                    // process ended
                }
            }, "ferry-py-output").apply { isDaemon = true; start() }
            val rx = Regex("""FERRY_READY port=(\d+) discovery=(\d+|off)""")
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val left = deadline - System.currentTimeMillis()
                val line = if (left > 0) lines.poll(minOf(left, 200L), TimeUnit.MILLISECONDS) else null
                if (line == null) {
                    if (left > 0 && proc.isAlive) continue
                    proc.destroyForcibly()
                    proc.waitFor(5, TimeUnit.SECONDS)
                    return null to output.toString()
                }
                val m = rx.find(line) ?: continue
                val p = m.groupValues[1].toInt()
                if (port != 0 && p != port) { // must be the same port
                    proc.destroyForcibly()
                    return null to output.toString()
                }
                val disc = m.groupValues[2].toIntOrNull()
                return FerryPcProcess(root, proc, output, HostPort("127.0.0.1", p), disc, freeBytes, appDir) to output.toString()
            }
        }
    }
}
