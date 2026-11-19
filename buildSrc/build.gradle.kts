// Home of the convention plugins shared by the framework modules.
//
// A precompiled script plugin in buildSrc is a regular Kotlin project to the IDE: Gradle API
// types inside it (PublishingExtension, MavenPublication, ...) resolve and complete normally.
// The same code as an applied `gradle/*.gradle.kts` script plugin is compiled fine by Gradle,
// but IDEs do not consistently attach the Gradle API classpath to such files, so those types
// show as unresolved references. Keeping the shared logic here gives IDE support and one
// definition for every module.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}
