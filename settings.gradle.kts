pluginManagement {
    repositories {
        // 插件本体发布到这里；demo 模块从同一仓库消费插件
        maven {
            name = "localSpi"
            url = uri("local-repo")
        }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // spi-annotation / spi-runtime 从本地仓库解析
        maven {
            name = "localSpi"
            url = uri("local-repo")
        }
        mavenCentral()
        google()
    }
}

rootProject.name = "android-spi"

// 插件本体（library 模块）
include(":spi-annotation")
include(":spi-runtime")
include(":spi-plugin")

// demo 主模块
include(":app")

// demo 非主模块，统一放在 modules/ 下
include(":modules:core-api")
include(":modules:core-common")
include(":modules:feature-home")
include(":modules:feature-account")
include(":modules:feature_camera")
