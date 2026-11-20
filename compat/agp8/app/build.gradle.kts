import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("top.jayvin.droidspi")
}

val spiVersion = providers.gradleProperty("spiVersion").getOrElse("1.0.0-alpha01")

android {
    namespace = "compat.agp8"
    compileSdk = 35

    defaultConfig {
        applicationId = "compat.agp8"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("top.jayvin.droidspi:annotation:$spiVersion")
    implementation("top.jayvin.droidspi:runtime:$spiVersion")
}
