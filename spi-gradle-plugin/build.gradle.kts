plugins {
    id("org.jetbrains.kotlin.jvm")
    id("java-gradle-plugin")
    id("maven-publish")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.ow2.asm:asm:9.7")
    compileOnly("com.android.tools.build:gradle:8.7.3")
    testImplementation("junit:junit:4.13.2")
}

gradlePlugin {
    plugins {
        create("spi") {
            id = "top.fishquant.mobile.spi"
            displayName = "Android SPI service loader"
            description = "编译期扫描 @ServiceProvider，生成固定全类名的 provider configuration"
            implementationClass = "top.fishquant.mobile.spi.plugin.SpiPlugin"
        }
    }
}

tasks.withType<Test>().configureEach {
    testLogging { events("passed", "failed", "skipped") }
}
