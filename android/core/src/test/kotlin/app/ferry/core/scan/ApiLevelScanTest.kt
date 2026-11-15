package app.ferry.core.scan

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `:core` must run on Fire OS 6 (API 25) [A3] [CP9]. `-Xjdk-release=1.8` rejects Java 9+ calls at compile
 * time; this scan rejects Java 8 APIs that Android only got at API 26+ (plan §3.5). Best-effort list; the
 * real proof is `:core` code running on the API 25 emulator.
 */
class ApiLevelScanTest {
    private val forbiddenPackages = listOf(
        "java/time/", "java/nio/file/", "java/util/Base64", "java/util/HexFormat", "java/lang/invoke/VarHandle",
        "java/util/concurrent/Flow", "kotlin/io/path/",
    )

    /** Classes forbidden as a whole (exact name; MethodHandles$Lookup appears in every indy lambda and is fine). */
    private val forbiddenClasses = setOf("java/lang/invoke/MethodHandles")

    private val forbiddenMethods: Map<String, Set<String>> = mapOf(
        "java/util/List" to setOf("of", "copyOf"),
        "java/util/Map" to setOf("of", "entry", "ofEntries", "copyOf"),
        "java/util/Set" to setOf("of", "copyOf"),
        "java/lang/String" to setOf("repeat", "isBlank", "strip", "stripLeading", "stripTrailing", "lines", "join"),
        "java/io/InputStream" to setOf("transferTo", "readAllBytes", "readNBytes"),
        "java/util/Optional" to setOf("isEmpty", "orElseThrow"),
        "java/lang/Integer" to setOf(
            "toUnsignedString", "toUnsignedLong", "parseUnsignedInt", "compareUnsigned", "divideUnsigned",
            "remainderUnsigned",
        ),
        "java/lang/Long" to setOf(
            "toUnsignedString", "parseUnsignedLong", "compareUnsigned", "divideUnsigned", "remainderUnsigned",
        ),
    )

    fun violations(scan: ClassScan): List<String> {
        val v = mutableListOf<String>()
        for (s in scan.utf8) {
            for (p in forbiddenPackages) {
                if (s.startsWith(p) || s.contains("L$p")) v += "references $p ($s)"
            }
        }
        for (c in scan.classes) {
            if (c in forbiddenClasses) v += "class $c"
        }
        for ((owner, name) in scan.methods) {
            if (owner in forbiddenClasses) v += "method $owner.$name"
            if (forbiddenMethods[owner]?.contains(name) == true) v += "method $owner.$name"
        }
        return v.distinct()
    }

    @Test
    fun coreUsesNoApi26PlusJavaApis() {
        val bad = ClassScan.coreClasses().flatMap { (path, scan) -> violations(scan).map { "$path: $it" } }
        assertTrue("API-26+ references in :core (Fire OS 6 would crash):\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    /** The scan itself can fail: a test class that uses java.time and List.of is caught. */
    @Test
    fun scanDetectsForbiddenReferences() {
        val self = ClassScan(javaClass.getResourceAsStream("/app/ferry/core/scan/ForbiddenSample.class")!!.readBytes())
        val v = violations(self)
        assertTrue(v.toString(), v.any { it.contains("java/time/") })
    }
}

/** Deliberately uses an API-26+ class; lives in the test source set only. */
@Suppress("unused")
class ForbiddenSample {
    fun now(): Any = java.time.Instant.now()
}
