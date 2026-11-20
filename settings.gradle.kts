pluginManagement {
    // Framework version is defined once in gradle.properties (spiVersion).
    plugins {
        id("top.jayvin.droidspi") version providers.gradleProperty("spiVersion").get()
    }
    repositories {
        // The framework is published here; demo modules consume the plugin from the same repository
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

// Demo main module
include(":app")

// Demo non-main modules, all under modules/
include(":modules:core-api")
include(":modules:core-common")
include(":modules:feature-home")
include(":modules:feature-account")
include(":modules:feature_camera")
