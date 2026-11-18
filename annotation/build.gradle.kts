plugins {
    id("java-library")
    id("maven-publish")
}

// Keep the annotation module plain Java so pure Java projects can reference it directly;
// Kotlin passes a Class argument via X::class.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
