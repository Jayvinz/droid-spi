plugins {
    id("com.android.application")
    id("top.jayvin.droidspi")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.droidreel"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.droidreel"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
        // The debug provider reads BUILD_TYPE / VERSION_NAME from BuildConfig.
        buildConfig = true
    }

    // Two flavor dimensions × two build types are exercised end to end (4 variants).
    // Distinct application ids let all four APKs be installed on one device for UI testing.
    flavorDimensions += "channel"
    productFlavors {
        create("free") {
            dimension = "channel"
            applicationIdSuffix = ".free"
        }
        create("vip") {
            dimension = "channel"
            applicationIdSuffix = ".vip"
        }
    }

    dynamicFeatures += setOf(":modules:production")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            // Installable side by side with the release build of the same flavor.
            applicationIdSuffix = ".debug"
        }
        release {
            // Signed with the debug key so demo release variants can be installed and exercised
            // on a device; this is a demo app, never shipped.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":modules:base"))
    implementation(project(":modules:account"))
    implementation(project(":modules:community"))

    // The app owns dynamic delivery; no other module depends on Play Core.
    implementation("com.google.android.play:feature-delivery:2.1.0")
}
