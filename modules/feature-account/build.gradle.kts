import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("top.jayvin.droidspi")
}

val spiVersion = providers.gradleProperty("spiVersion").get()

android {
    namespace = "com.example.feature.account"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// AGP 9 compiles Kotlin itself (built-in Kotlin); the Kotlin Android plugin is unnecessary.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":modules:core-api"))
    implementation("top.jayvin.droidspi:annotation:$spiVersion")
}
