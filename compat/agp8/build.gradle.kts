// AGP 8.7.3 requires Gradle 8.9; Kotlin 2.0.21 proves the runtime still serves consumers that
// have not moved to Kotlin 2.4 yet.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
