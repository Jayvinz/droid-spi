plugins {
    id("java-library")
    id("maven-publish")
}

// 注解模块保持纯 Java，保证纯 Java 项目能直接引用；Kotlin 侧用 X::class 传 Class 参数即可。
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
