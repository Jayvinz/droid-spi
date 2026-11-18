plugins {
    id("com.android.dynamic-feature")
    id("org.jetbrains.kotlin.android")
    id("com.example.spi")
}

android {
    namespace = "com.example.feature.camera"
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
    implementation(project(":app"))
    implementation(project(":core-api"))
    implementation(project(":core-common"))
    implementation("com.example.spi:spi-annotation:1.0.0")
    implementation("com.example.spi:spi-runtime:1.0.0")
}
