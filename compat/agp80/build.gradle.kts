// AGP 8.0.2 runs on Gradle 8.0+; this fixture pins Gradle 8.2. It covers the AGP 8.0-8.3
// window, where SourceDirectories.Flat has no `static` and the plugin falls back to `all`.
plugins {
    id("com.android.application") version "8.0.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
