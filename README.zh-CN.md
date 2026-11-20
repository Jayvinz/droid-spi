# Droid-spi

English: [README.md](README.md)

**面向 Android 的编译期服务发现。** 用 `@ServiceProvider` 注解（或标准 `META-INF/services` 文件）声明
provider，运行时**零反射**查找——包括位于按需下载的 dynamic feature 模块中的实现。

- **零反射** —— provider 全部以类字面量引用，没有 `Class.forName` 与反射构造，对移动端运行时性能友好。
- **零 APT/KSP** —— 不需要 APT、KSP 等耗时任务，对大型项目构建友好：支持增量构建、构建缓存、
  isolated projects，完整支持多 variant 与 buildType。
- **按 key 取用多实现** —— 一个接口可以有多个实现，查询时通过 `key` 指定所需实现。
- **绝不抛异常** —— 不可用时返回 `null` / 空集合。

## 集成

插件来自 **Gradle Plugin Portal**，库来自 **Maven Central**，不需要额外配置仓库或 token。

```kotlin
// settings.gradle.kts —— 如果项目里还没有声明
pluginManagement {
    repositories {
        gradlePluginPortal()   // 插件的仓库
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()         // annotation 和 runtime 的仓库
    }
}
```

```kotlin
// 模块 build.gradle.kts
plugins {
    // 每个声明 provider 的模块都要 apply 插件
    // 支持的模块类型：application、library、dynamic-feature
    id("top.jayvin.droidspi") version "1.0.0-alpha01"
}

dependencies {
    // provider 模块只需要 annotation
    implementation("top.jayvin.droidspi:annotation:1.0.0-alpha01")
    // application 模块，或其他需要用到 ServiceLoader 的地方
    implementation("top.jayvin.droidspi:runtime:1.0.0-alpha01")
}
```

## 声明 provider

service 接口放在 base / 常驻模块：

```kotlin
interface HomeService {
    fun title(): String
}
```

实现放在它所属的模块：

```kotlin
@ServiceProvider(service = HomeService::class)
class HomeServiceImpl : HomeService {
    override fun title() = "Home"
}

@ServiceProvider(service = HomeService::class, key = "vip")
class VipHomeServiceImpl : HomeService {
    companion object {
        @ProviderFactory @JvmStatic
        fun create(): VipHomeServiceImpl = VipHomeServiceImpl()
    }
}
```

纯 Java 工程：`@ServiceProvider(service = HomeService.class)`。已经提供 JDK SPI 配置文件的
第三方库，把 `META-INF/services/<接口 FQN>` 放进模块的 source resources 即可接入。

## 使用

```kotlin
// 可选：在 Application.onCreate 中提前初始化；不调用则在第一次查询时懒加载
ServiceLoader.initialize()

val home = ServiceLoader.load(HomeService::class.java)         // 默认实现，可能为 null
val vip  = ServiceLoader.load(HomeService::class.java, "vip")  // 指定 key，可能为 null
val all  = ServiceLoader.loadAll(HomeService::class.java)      // key -> 实例
val st   = ServiceLoader.stateOf(HomeService::class.java)      // READY / MISSING / DEAD
```

Kotlin 也支持 `ServiceLoader.load<HomeService>()`。`providers()` / `stream()` 枚举全部条目；
`loadClass()` 只取 class，不触发初始化。

### Dynamic feature

在 app 中声明模块（`dynamicFeatures += setOf(":modules:feature_camera")`）并对其 apply 插件。
安装前 provider 为 `MISSING`，安装后下一次 `load()` 返回实例；没有安装监听器，也不需要刷新。

### 多个默认实现

空 key 可以由多个实现共享。`load()` 返回第一个可用实现，顺序为
`(service, key, provider FQN)`；`providers()` 枚举全部。**非空 key 仍然必须唯一**，冲突会导致
构建失败。

## 了解更多

- 设计与实现：[docs/droid-spi-design.zh-CN.md](docs/droid-spi-design.zh-CN.md)
  （[English](docs/droid-spi-design.md)）
- 长文：[Droid-spi 介绍](docs/articles/01-introducing-droid-spi.zh-CN.md) ·
  [实现原理](docs/articles/02-how-droid-spi-works.zh-CN.md)
  （[English](docs/articles/01-introducing-droid-spi.md) ·
  [English](docs/articles/02-how-droid-spi-works.md)）

## 限制

- service 接口必须位于 base / 常驻模块，不能放在 dynamic feature 中。
- service 接口不支持嵌套类和 `typealias`。
- 需要 JDK 17、AGP 8.x、`minSdk 24`。
