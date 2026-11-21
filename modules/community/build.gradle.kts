plugins {
    id("com.android.library")
    id("top.jayvin.droidspi")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.droidreel.community"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    buildFeatures {
        compose = true
    }

    // The free / vip providers live in disjoint flavor source sets. The flavor names must match
    // the application, otherwise AGP cannot variant-match this library.
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
    implementation(project(":modules:base"))

    // Used only by the player screen; the SPI contracts do not depend on media playback.
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
}
