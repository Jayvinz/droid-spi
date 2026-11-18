plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // core-common has no @ServiceProvider annotations, but needs the plugin to scan
    // src/main/resources/META-INF/services
    id("top.jayvin.android.spi")
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":modules:core-api"))
}
