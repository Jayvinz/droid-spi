plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("top.jayvin.droidspi")
}

val spiVersion = providers.gradleProperty("spiVersion").get()

android {
    namespace = "com.example.feature.home"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    // Same dimension and flavor names as :app, so AGP matches free→free / paid→paid.
    // Each flavor contributes its own provider from src/free or src/paid.
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
    implementation(project(":modules:core-api"))
    implementation(project(":modules:core-common"))
    implementation("io.github.jayvinz:droidspi-annotation:$spiVersion")
}
