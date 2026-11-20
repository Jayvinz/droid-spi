import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    // core-common has no @ServiceProvider annotations, but needs the plugin to scan
    // src/main/resources/META-INF/services
    id("top.jayvin.droidspi")
}

android {
    namespace = "com.example.core.common"
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
}
