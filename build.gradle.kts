// Intentionally no plugin declarations here. Each module loads its own plugins, so in :app the
// Kotlin Gradle plugin is loaded together with the Android Gradle plugin (same classloader).
// Declaring Kotlin here but Android only in :app breaks AGP 9's built-in Kotlin support.
