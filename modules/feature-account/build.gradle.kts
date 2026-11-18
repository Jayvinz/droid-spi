plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("top.jayvin.android.spi")
}

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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":modules:core-api"))
    implementation("top.jayvin.android.spi:annotation:1.0.0")
}
