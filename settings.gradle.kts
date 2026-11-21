pluginManagement {
    // Framework version is defined once in gradle.properties (spiVersion).
    plugins {
        id("top.jayvin.droidspi") version providers.gradleProperty("spiVersion").get()
        // AGP 9's built-in Kotlin is KGP 2.2.10, so the Compose compiler plugin must match it.
        // Declared here rather than in the root build script: the root classpath also carries
        // Kotlin JVM 2.4.21 for the plugin module, and mixing the two Kotlin plugin versions
        // there makes Gradle's embedded-Kotlin annotation pin unresolvable.
        id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
    }
    repositories {
        // The framework is published here; the example modules consume the plugin from the same
        // repository.
        maven {
            name = "localSpi"
            url = uri("local-repo")
        }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

// No toolchain resolver plugin: gradle/gradle-daemon-jvm.properties pins the daemon to JDK 17,
// and Gradle's built-in detection resolves the same locally installed JDK for jvmToolchain(17).

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // annotation / runtime are resolved from the local repository
        maven {
            name = "localSpi"
            url = uri("local-repo")
        }
        mavenCentral()
        google()
    }
}

rootProject.name = "droid-spi"

// Framework modules (libraries)
include(":annotation")
include(":runtime")
include(":plugin")
include(":configuration-stub")

// Example application (DroidReel) and its feature modules. The application is the only module
// that generates the ServiceConfiguration table; the others only contribute providers.
include(":app")
include(":modules:base")
include(":modules:account")
include(":modules:community")
include(":modules:production")
