// Pinned, mutually compatible set (from memory - CI is the first real check):
//   Gradle 8.9 (gradle-wrapper.properties) | AGP 8.7.3 | Kotlin 2.0.21 + Compose compiler plugin 2.0.21
//   Compose BOM 2024.10.01 (app/build.gradle.kts) | JDK 17 | compileSdk 35 / targetSdk 34 / minSdk 30
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
