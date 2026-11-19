# droid-spi

[![CI](https://github.com/Garenzhou/android-spi/actions/workflows/ci.yml/badge.svg)](https://github.com/Garenzhou/android-spi/actions/workflows/ci.yml)

面向 Android 的编译期服务发现。用 `@ServiceProvider` 注解或标准 `META-INF/services` 文件声明
provider，Gradle 插件生成一个固定类名的配置类，运行时**零反射**解析——包括位于按需下载的
dynamic feature 模块中的 provider。

- **零反射、零 APT/KSP**：provider 全部以类字面量引用。
- **按变体生成配置表**：`freeDebug`、`paidDebug`……每个变体各自一份配置类。
- **适配动态下发**：模块未安装时 provider 为 `MISSING`，安装后下一次 `load()` 即可用，无需
  `refresh()`。
- **门面不抛异常**：查不到时返回 `null` / 空集合。

English: [README](README.md)

## 接入

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/Garenzhou/android-spi")
            credentials {
                username = providers.gradleProperty("gpr.user").get()
                password = providers.gradleProperty("gpr.key").get()
            }
        }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/Garenzhou/android-spi")
            credentials {
                username = providers.gradleProperty("gpr.user").get()
                password = providers.gradleProperty("gpr.key").get()
            }
        }
        google()
        mavenCentral()
    }
}
```

`gpr.user` 填 GitHub 用户名，`gpr.key` 填带 `read:packages` 权限的 token（GitHub Packages
即使读取公开产物也需要认证）。

```kotlin
// 模块 build.gradle.kts
plugins {
    id("com.android.application")     // 也可以是 com.android.library / com.android.dynamic-feature
    id("org.jetbrains.kotlin.android")
    id("top.jayvin.droidspi") version "1.0.0-alpha01"
}

dependencies {
    implementation("top.jayvin.droidspi:annotation:1.0.0-alpha01")
    implementation("top.jayvin.droidspi:runtime:1.0.0-alpha01")
}
```

## 使用

**1. 声明 provider** —— service 接口必须放在 base 模块。

```kotlin
// :core-api
interface HomeService { fun title(): String }

// :feature-home
@ServiceProvider(service = HomeService::class)
class HomeServiceImpl : HomeService { override fun title() = "Home" }

// 同一接口的变体实现
@ServiceProvider(service = HomeService::class, key = "vip")
class VipHomeServiceImpl : HomeService {
    companion object {
        @ProviderFactory @JvmStatic
        fun create(): VipHomeServiceImpl = VipHomeServiceImpl()
    }
}
```

纯 Java 工程写法相同：`@ServiceProvider(service = HomeService.class)`。

**2. 启动时初始化一次**：

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLoader.initialize()
    }
}
```

**3. 在业务代码中查找**：

```kotlin
val home = ServiceLoader.load(HomeService::class.java)        // 默认实现，可能为 null
val vip  = ServiceLoader.load(HomeService::class.java, "vip") // 指定 key
val all  = ServiceLoader.loadAll(HomeService::class.java)     // key -> 实例
val cls  = ServiceLoader.loadClass(HomeService::class.java)   // 只取 class，不触发 <clinit>
val st   = ServiceLoader.stateOf(HomeService::class.java)     // READY / MISSING / DEAD

ServiceLoader.stream(HomeService::class.java).forEach { provider ->
    Log.d("demo", provider.type().name)
}
```

Kotlin 还提供 `ServiceLoader.load<HomeService>()`。所有查询在不可用时返回 `null` / 空集合，
不会抛异常。

### 标准 `META-INF/services`

已经按 JDK SPI 规范提供配置文件的第三方库无需改代码：把文件放进模块的 source resources
即可，插件默认会扫描（`spi { scanMetaInfServices = true }`）。其中的条目映射到默认 key，并要求
实现类有 public 无参构造函数。

### Dynamic feature 模块

```kotlin
android { dynamicFeatures += setOf(":modules:feature_camera") }
```

dynamic feature 模块同样需要 apply 插件（编译期只需要 `annotation` 依赖）。它和其他模块一样
贡献 provider：安装前为 `MISSING`，安装后下一次 `load()` 直接返回实例。既没有安装监听器，
也没有 `refresh()`。

## 本地构建

```bash
# local-repo/ 已被 gitignore；仅 fresh clone 首次发布需要 --configure-on-demand
./gradlew :annotation:publish :runtime:publish :plugin:publish --configure-on-demand

./gradlew :runtime:testDebugUnitTest
./gradlew :app:assembleFreeDebug :app:assemblePaidDebug :app:assembleFreeRelease :app:assemblePaidRelease
```

环境要求：JDK 17、Android Gradle Plugin 8.x、`minSdk 24`。

## 实现原理（高级）

```
各模块按变体                                app 按变体                       运行时
@ServiceProvider / META-INF  ──扫描──▶ services.txt ──聚合──▶ ServiceConfiguration.class
                                                                        │
                                                     Application.onCreate └─ ServiceLoader.initialize()
                                                                        │
                                                     安装后的下一次 load() ──▶ provider 实例
```

1. 每个模块变体扫描自己的源码目录（main + buildType + flavor 覆盖层），把结果以
   `spi-manifest` 类型的 Gradle artifact 发布到 runtime elements。
2. app 变体通过 variant-aware 依赖解析读取这些清单，校验 `(service, key)` 唯一性，再用 ASM
   生成 `ServiceConfiguration` 字节码。生成类**没有 `<clinit>`**：provider 类字面量只出现在方法体
   内，因此未安装的 dynamic module 不会污染整张表。
3. 运行时 `ProviderRegistry` 按需探测：`NoClassDefFoundError` 转为 `null`/`MISSING`，下次重试；
   只有 `<clinit>` 失败（`DEAD`）会被缓存。

完整设计、不变量、缓存与 isolated-projects 说明、回归矩阵：
[docs/droid-spi-design.zh-CN.md](docs/droid-spi-design.zh-CN.md) · [English](docs/droid-spi-design.md)。

长文： [droid-spi 介绍](docs/articles/01-introducing-droid-spi.zh-CN.md) ·
[实现原理](docs/articles/02-how-droid-spi-works.zh-CN.md)
（[English](docs/articles/01-introducing-droid-spi.md) ·
[English](docs/articles/02-how-droid-spi-works.md)）。

## 限制

- service 接口必须位于 base / 常驻模块，不能放在 dynamic feature 中。
- 每个 `(service, key)` 全局只能有一个 provider，冲突会导致构建失败。
- service 接口不支持嵌套类和 `typealias`。
- R8 需要为可能缺失的模块添加 `-dontwarn`（见 `app/proguard-rules.pro`）。
