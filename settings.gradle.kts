pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "android-spi"

include(":spi-annotation")
include(":spi-runtime")
include(":spi-gradle-plugin")
