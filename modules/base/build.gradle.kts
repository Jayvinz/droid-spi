plugins {
    id("com.android.library")
    id("top.jayvin.droidspi")
    id("org.jetbrains.kotlin.plugin.compose")
}

val spiVersion = providers.gradleProperty("spiVersion").get()

android {
    namespace = "com.example.droidreel.base"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The framework is consumed from local-repo in this build. base exposes it with `api` so
    // every feature module can declare only project(":modules:base").
    api("top.jayvin.droidspi:annotation:$spiVersion")
    api("top.jayvin.droidspi:runtime:$spiVersion")

    // Shared dependency catalog: business modules declare only project(":modules:base").
    api(platform("androidx.compose:compose-bom:2025.06.01"))
    api("androidx.compose.ui:ui")
    api("androidx.compose.foundation:foundation")
    api("androidx.compose.material3:material3")
    api("androidx.compose.material:material-icons-core")
    api("androidx.activity:activity-compose:1.10.1")
    api("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    api("androidx.core:core-ktx:1.15.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
