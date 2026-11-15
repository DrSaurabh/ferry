package app.ferry.core.scan

import java.io.DataInputStream
import java.io.File

/** Minimal class-file constant-pool reader for the API-level and no-android scans (plan §3.5). */
class ClassScan(bytes: ByteArray) {
    /** Names of CONSTANT_Class entries (internal form, e.g. java/time/Instant or [Ljava/time/Instant;). */
    val classes = mutableSetOf<String>()
    /** (owner, name) of every Methodref / InterfaceMethodref. */
    val methods = mutableSetOf<Pair<String, String>>()
    /** Every Utf8 entry (descriptors, signatures, metadata strings, literals). */
    val utf8 = mutableListOf<String>()

    init {
        val d = DataInputStream(bytes.inputStream())
        require(d.readInt() == 0xCAFEBABE.toInt()) { "not a class file" }
        d.readUnsignedShort(); d.readUnsignedShort() // minor, major
        val n = d.readUnsignedShort()
        val tags = IntArray(n)
        val a = IntArray(n)
        val b = IntArray(n)
        val strings = arrayOfNulls<String>(n)
        var i = 1
        while (i < n) {
            val tag = d.readUnsignedByte()
            tags[i] = tag
            when (tag) {
                1 -> strings[i] = d.readUTF()
                3, 4 -> d.readInt()
                5, 6 -> { d.readLong(); i++ }
                7, 8, 16, 19, 20 -> a[i] = d.readUnsignedShort()
                9, 10, 11, 12, 17, 18 -> { a[i] = d.readUnsignedShort(); b[i] = d.readUnsignedShort() }
                15 -> { d.readUnsignedByte(); a[i] = d.readUnsignedShort() }
                else -> error("unknown constant tag $tag")
            }
            i++
        }
        for (k in 1 until n) {
            when (tags[k]) {
                1 -> utf8 += strings[k]!!
                7 -> classes += strings[a[k]]!!
                10, 11 -> {
                    val owner = strings[a[a[k]]]!!
                    val name = strings[a[b[k]]]!!
                    methods += owner to name
                }
            }
        }
    }

    companion object {
        /** The compiled `:core` main classes (path set by Gradle). Fails if there are suspiciously few. */
        fun coreClasses(): List<Pair<String, ClassScan>> {
            val dir = File(System.getProperty("ferry.coreClasses") ?: "build/classes/kotlin/main")
            val files = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.toList()
            check(files.size >= 10) { "only ${files.size} class files under $dir" }
            return files.map { it.relativeTo(dir).path to ClassScan(it.readBytes()) }
        }
    }
}
