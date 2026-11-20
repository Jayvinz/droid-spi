import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.javadoc.Javadoc

plugins {
    `maven-publish`
    signing
}

// Shared publishing setup for the framework modules (group, version, POM metadata, signing).
//
// Why this file lives in buildSrc instead of an applied `gradle/*.gradle.kts` script plugin:
// Gradle can compile applied Kotlin script plugins, but IDEs do not reliably put the Gradle API
// on their classpath, so types such as PublishingExtension appear as unresolved references.
// A precompiled script plugin under buildSrc is indexed as a normal Kotlin project, which keeps
// the editor clean and still gives every module one shared definition.
//
// Everything below is project-local (module-relative path, Gradle property), so it stays
// compatible with isolated projects; no project reads another project's model.

group = "top.jayvin.droidspi"
version = providers.gradleProperty("spiVersion").get()

// Javadoc defaults to the platform encoding; the sources contain non-ASCII characters and CI
// runs on Linux while local builds run on Windows, so pin UTF-8 for reproducible jars.
tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
}

publishing {
    // Local builds publish here and nowhere else. Production publishing is done by the release
    // workflow only: it uploads a signed bundle to Maven Central and runs `publishPlugins` for
    // the Gradle Plugin Portal. No production repository is configured in the build itself.
    repositories {
        maven {
            name = "local"
            url = layout.projectDirectory.dir("../local-repo").asFile.toURI()
        }
    }

    // Maven Central requires complete POM metadata for every artifact.
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("droid-spi ${project.name}")
            description.set(
                "Compile-time SPI for Android: scans @ServiceProvider and generates a " +
                    "fixed-FQN provider configuration class",
            )
            url.set("https://github.com/Jayvinz/droid-spi")
            licenses {
                license {
                    name.set("Apache License 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("Jayvinz")
                    name.set("Jayvin")
                }
            }
            scm {
                url.set("https://github.com/Jayvinz/droid-spi")
                connection.set("scm:git:git://github.com/Jayvinz/droid-spi.git")
                developerConnection.set("scm:git:ssh://git@github.com/Jayvinz/droid-spi.git")
            }
        }
    }
}

// Signing is required by Maven Central and only enabled when the release workflow provides the
// key. A local `publish` therefore produces unsigned artifacts in local-repo and cannot release.
val signingKey = providers.environmentVariable("SIGNING_KEY").orNull
signing {
    // Without this, local publishes fail on signing tasks that have no signatory (the Gradle
    // plugin publication gets signing tasks from the plugin-publish plugin).
    isRequired = !signingKey.isNullOrBlank()
    if (!signingKey.isNullOrBlank()) {
        // Gradle registers no signatory when the password is null; an unencrypted key therefore
        // needs an empty string, not null.
        useInMemoryPgpKeys(
            signingKey,
            providers.environmentVariable("SIGNING_PASSWORD").orNull ?: "",
        )
    }
}
if (!signingKey.isNullOrBlank()) {
    publishing.publications.withType<MavenPublication>().configureEach {
        signing.sign(this)
    }
}
