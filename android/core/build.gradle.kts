import java.time.Duration

// :core — pure Kotlin/JVM, no android.* (plan §3.1). Java 8 bytecode and API so it runs on Fire OS 6 (API 25) [A3] [CP9].
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
tasks.withType<JavaCompile>().configureEach { options.release.set(8) }
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-Xjdk-release=1.8") // [CP9]
    }
}

dependencies {
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json) // api, not implementation [PA11] [PR5]
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    systemProperty("ferry.pcDir", rootDir.parentFile.resolve("pc").absolutePath)
    systemProperty(
        "ferry.python",
        System.getenv("FERRY_PYTHON") ?: if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3",
    )
    systemProperty("ferry.requirePython", System.getenv("CI") ?: "false")
    // ApiLevelScanTest / NoAndroidImportsTest read the compiled main classes (plan §3.7).
    systemProperty("ferry.coreClasses", layout.buildDirectory.dir("classes/kotlin/main").get().asFile.absolutePath)
    maxParallelForks = 1
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    timeout.set(Duration.ofMinutes(20))
}
