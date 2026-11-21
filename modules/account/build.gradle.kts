plugins {
    id("com.android.library")
    id("top.jayvin.droidspi")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.droidreel.account"
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
    implementation(project(":modules:base"))
}
