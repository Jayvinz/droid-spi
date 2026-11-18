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

    dynamicFeatures += setOf(":modules:feature_camera")

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

dependencies {
    implementation(project(":modules:core-api"))
    implementation(project(":modules:core-common"))
    implementation(project(":modules:feature-home"))
    implementation(project(":modules:feature-account"))

    implementation("top.jayvin.android.spi:annotation:$version")
    implementation("top.jayvin.android.spi:runtime:$version")
    // demo 自己负责动态模块安装与可见性
    implementation("com.google.android.play:feature-delivery:2.1.0")
}
