plugins {
    // Declared here so all modules share one Kotlin Gradle plugin. The Android plugin is only
    // applied in :app, which is only included when an Android SDK is present.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose) apply false
}
