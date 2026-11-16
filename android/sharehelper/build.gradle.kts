// Test-only helper app (plan CP21 [PB6] [H33]): shares three files from its own non-exported FileProvider to
// Ferry's ShareReceiverActivity, so ShareToFerryTest can only pass if the read grant is forwarded. Debug build only;
// installed on the phone emulator, never packaged.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.ferry.sharehelper"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.ferry.sharehelper"
        minSdk = 25
        targetSdk = 35
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
}
