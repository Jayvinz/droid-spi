plugins {
    id("org.jetbrains.kotlin.jvm")
    id("java-gradle-plugin")
    id("maven-publish")
    id("framework-publishing")
}

kotlin {
    jvmToolchain(17)
}

// The Gradle plugin publication is created from the java component, so this also attaches
// plugin-<version>-sources.jar to pluginMaven.
java {
    withSourcesJar()
}

dependencies {
    implementation("org.ow2.asm:asm:9.7")
    compileOnly("com.android.tools.build:gradle:8.7.3")
}

gradlePlugin {
    plugins {
        create("spi") {
            id = "top.jayvin.droidspi"
            displayName = "droid-spi"
            description = "Compile-time SPI for Android: scans @ServiceProvider and generates a fixed-FQN provider configuration class"
            implementationClass = "top.jayvin.android.spi.plugin.SpiPlugin"
        }
    }
}
