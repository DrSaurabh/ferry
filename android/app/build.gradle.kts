import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version [H26] [CP13]: android.yml sets FERRY_RELEASE=1 and FERRY_RUN_NUMBER on main only.
// Same rule as app.ferry.VersionRule (VersionRuleTest checks that BuildConfig follows it).
val ferryRelease = System.getenv("FERRY_RELEASE") == "1"
val ferryRun = System.getenv("FERRY_RUN_NUMBER")?.toIntOrNull()
val ferryVersionCode = if (ferryRelease && ferryRun != null) 20000 + ferryRun else 1
val ferryVersionName = if (ferryRelease && ferryRun != null) "2.0.$ferryRun" else "2.0.0-dev"

// Release signing [H38]: the keystore is committed ENCRYPTED (android/signing/ferry-release.jks.enc).
// CI decrypts it before the build; the passphrase (also store/key password) is the repository
// secret FERRY_SIGNING_PASS. Without it the release APK is left unsigned and check_apk.py fails.
val signingProps = Properties().apply {
    rootProject.file("signing/signing.properties").inputStream().use { load(it) }
}
val signingPass: String? = System.getenv("FERRY_SIGNING_PASS")?.takeIf { it.isNotEmpty() }
val signingStore = rootProject.file(signingProps.getProperty("storeFile"))
val canSign = signingPass != null && signingStore.exists()

android {
    namespace = "app.ferry"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.ferry"
        minSdk = 25
        targetSdk = 35
        versionCode = ferryVersionCode
        versionName = ferryVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = signingStore
                storePassword = signingPass
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingPass
            }
        }
    }
    buildTypes {
        release {
            if (canSign) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // [A3]
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint {
        checkOnly += setOf("NewApi")
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(project(":core"))
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(libs.kotlinx.coroutines.android)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1") // TV player's PlayerView (Ph8b, plan §0.3)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0") // picker thumbnails (Ph6), TV thumbnails (Ph8a)
    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(bom)
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
