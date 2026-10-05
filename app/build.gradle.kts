plugins {
    id("com.android.application") version "8.7.0"
    id("org.jetbrains.kotlin.android") version "1.9.22"
}

android {
    namespace = "com.yourname.spoofer"
    compileSdk = 35 // Android 15

    defaultConfig {
        applicationId = "com.yourname.spoofer"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // The modern Xposed API (compiled only, not bundled)
    compileOnly("de.robv.android.xposed:api:82")
}
