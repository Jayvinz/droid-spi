// Compatibility fixture: consumes the framework plugin from local-repo with AGP 8.0.2,
// Kotlin 2.0.21 and Gradle 8.2. It covers the pre-8.4 source API fallback. Publish the
// framework modules to local-repo first (see README.md).
pluginManagement {
    val spiVersion = providers.gradleProperty("spiVersion").get()
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

rootProject.name = "spi-agp80-compat"
include(":app")
