import org.gradle.api.publish.PublishingExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.maven

// Shared publishing setup for the framework modules, applied from each module's build script.
// Everything here is project-local (module-relative path, Gradle property), so it stays
// compatible with isolated projects; no project reads another project's model.

project.group = "top.jayvin.android.spi"
project.version = project.providers.gradleProperty("spiVersion").get()

project.extensions.configure<PublishingExtension> {
    repositories {
        maven {
            name = "local"
            url = project.layout.projectDirectory.dir("../local-repo").asFile.toURI()
        }
    }
}
