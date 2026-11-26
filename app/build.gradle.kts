plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.ctsmapper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.ctsmapper"
        minSdk = 30
        targetSdk = 34
        // CI bumps it on every run so Shizuku restarts the helper after each update (RawInput.userArgs uses it).
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1"
    }
    signingConfigs {
        // Fixed key committed to the repo so every CI debug APK is signed the same and updates in place.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Lets a normal app call hidden framework APIs via reflection (no root). Same lib MiCTS-style apps use.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    // Optional "raw input" mode (Plus Key): Shizuku user service
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
