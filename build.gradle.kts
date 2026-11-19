plugins {
    id("com.android.library") version "8.7.3" apply false
    id("com.android.application") version "8.7.3" apply false
    id("com.android.dynamic-feature") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    // The framework plugin is deliberately not declared here: even `apply false` resolves it
    // during configuration, which breaks the first publish on a fresh clone (local-repo empty).
    // Subprojects take the version from settings pluginManagement.
}

// Modules are configured only by their own build scripts; the root performs no cross-project
// configuration, which keeps the build compatible with isolated projects.
