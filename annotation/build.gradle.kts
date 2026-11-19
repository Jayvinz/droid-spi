plugins {
    id("java-library")
    id("maven-publish")
    id("framework-publishing")
}

// Keep the annotation module plain Java so pure Java projects can reference it directly;
// Kotlin passes a Class argument via X::class.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    // Maven Central requires a javadoc jar for every artifact.
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "droidspi-annotation"
            from(components["java"])
        }
    }
}
