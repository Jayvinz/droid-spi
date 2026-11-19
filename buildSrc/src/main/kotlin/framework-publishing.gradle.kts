plugins {
    `maven-publish`
}

// Shared publishing setup for the framework modules (group, version, local-repo destination).
//
// Why this file lives in buildSrc instead of an applied `gradle/*.gradle.kts` script plugin:
// Gradle can compile applied Kotlin script plugins, but IDEs do not reliably put the Gradle API
// on their classpath, so types such as PublishingExtension appear as unresolved references.
// A precompiled script plugin under buildSrc is indexed as a normal Kotlin project, which keeps
// the editor clean and still gives every module one shared definition.
//
// Everything below is project-local (module-relative path, Gradle property), so it stays
// compatible with isolated projects; no project reads another project's model.

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
