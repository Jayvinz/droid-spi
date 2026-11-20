plugins {
    id("com.android.library") version "9.5.1" apply false
    id("com.android.application") version "9.5.1" apply false
    id("com.android.dynamic-feature") version "9.5.1" apply false
    // AGP 9 provides Kotlin support for Android modules itself (built-in Kotlin), so the
    // org.jetbrains.kotlin.android plugin is not applied anywhere. Kotlin JVM is still used by
    // the Gradle plugin module.
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    // The framework plugin is deliberately not declared here: even `apply false` resolves it
    // during configuration, which breaks the first publish on a fresh clone (local-repo empty).
    // Subprojects take the version from settings pluginManagement.
}

// Modules are configured only by their own build scripts; the root performs no cross-project
// configuration, which keeps the build compatible with isolated projects.
