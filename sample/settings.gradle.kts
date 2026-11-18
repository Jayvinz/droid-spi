pluginManagement {
    repositories {
        maven {
            name = "localSpi"
            url = uri("../local-repo")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            name = "localSpi"
            url = uri("../local-repo")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "spi-sample"

include(":app")
include(":core-api")
include(":core-common")
include(":feature-home")
include(":feature-account")
include(":feature_camera")
