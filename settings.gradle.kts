pluginManagement {
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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

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

rootProject.name = "android-spi"

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
