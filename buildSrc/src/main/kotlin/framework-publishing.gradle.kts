plugins {
    `maven-publish`
}

// Shared publishing setup for the framework modules. Everything is project-local
// (module-relative path, Gradle property), so it stays compatible with isolated projects;
// no project reads another project's model.

group = "top.jayvin.android.spi"
version = providers.gradleProperty("spiVersion").get()

publishing {
    repositories {
        maven {
            name = "local"
            url = layout.projectDirectory.dir("../local-repo").asFile.toURI()
        }
    }
}
