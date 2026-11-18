plugins {
    id("com.android.library") version "8.7.3" apply false
    id("com.android.application") version "8.7.3" apply false
    id("com.android.dynamic-feature") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    // Demo modules reference the plugin by id (without a version); the version is declared
    // once here, while the artifact itself comes from local-repo.
    id("top.jayvin.android.spi") version "1.0.0" apply false
}

val localRepo = rootProject.layout.projectDirectory.dir("local-repo")

subprojects {
    group = "top.jayvin.android.spi"
    version = "1.0.0"

    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension>("publishing") {
            repositories {
                maven {
                    name = "local"
                    url = uri(localRepo)
                }
            }
        }
    }
}
