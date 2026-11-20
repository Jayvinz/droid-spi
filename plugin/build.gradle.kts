import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("java-gradle-plugin")
    id("maven-publish")
    id("com.gradle.plugin-publish") version "2.2.1"
    id("framework-publishing")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // Consumers' Kotlin DSL compiles build scripts with the Kotlin embedded in their Gradle
        // distribution (1.8 on Gradle 8.0-8.3, 1.9 on 8.4-8.10), which can read metadata up to
        // 2.0.0. Publishing 2.4 metadata would make `spi { ... }` fail there with "incompatible
        // metadata". Kotlin 2.4 no longer supports a language/api level below 2.0, so pin those
        // at 2.0 and only lower the metadata version.
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        freeCompilerArgs.add("-Xmetadata-version=2.0.0")
    }
}

// The Gradle plugin publication is created from the java component, so this also attaches
// plugin-<version>-sources.jar and plugin-<version>-javadoc.jar to pluginMaven.
java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    implementation("org.ow2.asm:asm:9.7")
    // Compatibility baseline: compile against the lowest supported AGP so one artifact serves
    // AGP 8.7-8.13 and 9.x. Do not raise this to use newer AGP APIs; use only members whose
    // descriptors are identical in 8.7.3 and 9.5.1 (see the AGP compatibility notes in CLAUDE.md).
    compileOnly("com.android.tools.build:gradle:8.7.3")
    testImplementation("junit:junit:4.13.2")
}

gradlePlugin {
    website.set("https://github.com/Jayvinz/droid-spi")
    vcsUrl.set("https://github.com/Jayvinz/droid-spi")
    plugins {
        create("spi") {
            id = "top.jayvin.droidspi"
            displayName = "droid-spi"
            description = "Compile-time SPI for Android: scans @ServiceProvider and generates a fixed-FQN provider configuration class"
            implementationClass = "top.jayvin.android.spi.plugin.SpiPlugin"
            tags.set(listOf("android", "spi", "service-loader", "dynamic-features", "aab"))
        }
    }
}

// Production publishing is CI-only; a local `publishPlugins` is a no-op.
tasks.matching { it.name == "publishPlugins" }.configureEach {
    onlyIf { System.getenv("CI") == "true" }
}
