plugins {
    id("com.android.dynamic-feature")
    id("org.jetbrains.kotlin.android")
    id("top.jayvin.droidspi")
}

val spiVersion = providers.gradleProperty("spiVersion").get()

android {
    namespace = "com.example.feature.camera"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    // Dynamic features depend on the base :app module, so they must declare the same flavor
    // dimension; otherwise the base variant is ambiguous when the feature resolves :app.
    flavorDimensions += "channel"
    productFlavors {
        create("free") { dimension = "channel" }
        create("paid") { dimension = "channel" }
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
    implementation(project(":modules:core-api"))
    implementation(project(":modules:core-common"))
    implementation("top.jayvin.droidspi:annotation:$spiVersion")
}
