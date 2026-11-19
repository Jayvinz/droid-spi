plugins {
    id("com.android.library") version "8.7.3" apply false
    id("com.android.application") version "8.7.3" apply false
    id("com.android.dynamic-feature") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    // Version comes from gradle.properties (spiVersion) via settings pluginManagement.
    id("top.jayvin.android.spi") apply false
}

// Modules are configured only by their own build scripts; the root performs no cross-project
// configuration, which keeps the build compatible with isolated projects.
