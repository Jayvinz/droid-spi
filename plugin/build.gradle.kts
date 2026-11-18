plugins {
    id("org.jetbrains.kotlin.jvm")
    id("java-gradle-plugin")
    id("maven-publish")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.ow2.asm:asm:9.7")
    compileOnly("com.android.tools.build:gradle:8.7.3")
}

gradlePlugin {
    plugins {
        create("spi") {
            id = "top.jayvin.android.spi"
            displayName = "Android SPI service loader"
            description = "Scans @ServiceProvider at compile time and generates a fixed-FQN provider configuration class"
            implementationClass = "top.jayvin.android.spi.plugin.SpiPlugin"
        }
    }
}
