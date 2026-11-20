// Compatibility fixture: consumes the framework plugin from local-repo with AGP 8.7.3 and
// Kotlin 2.0.21 on Gradle 8.9. The main build exercises AGP 9; this fixture keeps the 8.x line
// covered. Publish the framework modules to local-repo before running it (see README.md).
pluginManagement {
    val spiVersion = providers.gradleProperty("spiVersion").getOrElse("1.0.0-alpha01")
    plugins {
        id("top.jayvin.droidspi") version spiVersion
    }
    repositories {
        maven { url = uri("../../local-repo") }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("../../local-repo") }
        google()
        mavenCentral()
    }
}

rootProject.name = "spi-agp8-compat"
include(":app")
