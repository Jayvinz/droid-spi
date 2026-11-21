plugins {
    id("com.android.dynamic-feature")
    id("top.jayvin.droidspi")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.droidreel.production"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    buildFeatures {
        compose = true
    }

    // A dynamic feature always depends on the base application, so it must declare the same
    // flavor dimension and names to let AGP choose a base variant.
    flavorDimensions += "channel"
    productFlavors {
        create("free") { dimension = "channel" }
        create("vip") { dimension = "channel" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Required by AGP: a dynamic feature always depends on the base application.
    implementation(project(":app"))
    implementation(project(":modules:base"))
}
