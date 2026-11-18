plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("top.jayvin.android.spi")
}

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

    dynamicFeatures += setOf(":feature_camera")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

spi {
    generatedPackage = "top.jayvin.android.spi.generated"
    generatedClassName = "GeneratedServiceConfiguration"
}

dependencies {
    implementation(project(":core-api"))
    implementation(project(":core-common"))
    implementation(project(":feature-home"))
    implementation(project(":feature-account"))

    implementation("top.jayvin.android.spi:spi-annotation:1.0.0")
    // runtime 会传递 play-core（SplitInstallManager / SplitCompat）
    implementation("top.jayvin.android.spi:spi-runtime:1.0.0")
}
