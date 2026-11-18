plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.example.spi")
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
    implementation(project(":core-api"))
    implementation("com.example.spi:spi-annotation:1.0.0")
}
