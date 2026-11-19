plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("top.jayvin.droidspi")
}

val spiVersion = providers.gradleProperty("spiVersion").get()

android {
    namespace = "com.example.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.demo"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        // The self-check screen reads BUILD_TYPE / FLAVOR / DEBUG from BuildConfig.
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
        create("paid") {
            dimension = "channel"
            applicationIdSuffix = ".paid"
        }
    }

    dynamicFeatures += setOf(":modules:feature_camera")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            // Installable side by side with the release build of the same flavor.
            applicationIdSuffix = ".debug"
        }
        release {
            // Signed with the debug key so the demo release variants can be installed and
            // exercised on a device; this is a demo app, never shipped.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(project(":modules:core-api"))
    implementation(project(":modules:core-common"))
    implementation(project(":modules:feature-home"))
    implementation(project(":modules:feature-account"))

    implementation("top.jayvin.droidspi:annotation:$spiVersion")
    implementation("top.jayvin.droidspi:runtime:$spiVersion")
    // The demo owns dynamic-module installation and visibility.
    implementation("com.google.android.play:feature-delivery:2.1.0")
}
