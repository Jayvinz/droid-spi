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
    id("top.jayvin.droidspi") version "1.0.0-alpha02"
}

dependencies {
    // provider 模块只需要 annotation
    implementation("top.jayvin.droidspi:annotation:1.0.0-alpha02")
    // application 模块，或其他需要用到 ServiceLoader 的地方
    implementation("top.jayvin.droidspi:runtime:1.0.0-alpha02")
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
        // 这里既可以 new，也可以直接提供类已经持有的实例（见下文“构造方式与 @ProviderFactory”）。
        @ProviderFactory @JvmStatic
        fun create(): VipHomeServiceImpl = VipHomeServiceImpl()
    }
}
```

纯 Java 工程：`@ServiceProvider(service = HomeService.class)`。已经提供 JDK SPI 配置文件的
第三方库，把 `META-INF/services/<接口 FQN>` 放进模块的 source resources 即可接入。

### 构造方式与 `@ProviderFactory`

生成表默认用 `new Impl()` 实例化 provider。把 `@ProviderFactory` 标在一个**静态无参**方法上，
构造就交给 provider 类自己完成——当构造函数是 private 或需要参数时也必须这样做。生成的字节码
用 `INVOKESTATIC` 调用该工厂（依然零反射），方法可以返回**类自己已经持有的实例**：单例、DI
容器里的对象或预先构造好的对象。框架不缓存实例——每次 `load()` / `Provider.get()` 都会调用
工厂——因此希望共享同一个对象时，在工厂里返回缓存字段。

```kotlin
@ServiceProvider(service = HomeService::class, key = "vip")
class VipHomeServiceImpl private constructor() : HomeService {
    override fun title() = "VIP"

    companion object {
        // 只创建一次，交给所有调用方；框架自身不做实例缓存。
        private val shared = VipHomeServiceImpl()

        // Kotlin 必须写成 companion object 里的 @JvmStatic；每个类最多一个工厂。
        @ProviderFactory @JvmStatic
        fun create(): VipHomeServiceImpl = shared
    }
}
```

```java
@ServiceProvider(service = HomeService.class, key = "vip")
public class VipHomeServiceImpl implements HomeService {
    private static final VipHomeServiceImpl INSTANCE = new VipHomeServiceImpl();

    @ProviderFactory
    public static VipHomeServiceImpl create() { return INSTANCE; }
}
```

工厂必须是 `static`、无参，并返回 provider 类自身（不是接口）——生成器发出
`INVOKESTATIC Impl.create()LImpl;`。构建期会校验静态形式与“每个类最多一个工厂”；无参与返回类型
两条不在构建期校验，违反时首次加载抛 `NoSuchMethodError`。`stateOf()` / `loadClass()` 仍然只用
provider 类字面量，所以可用性与 dynamic feature 行为不变。工厂抛出异常时，该次 `load()` 返回
`null`，下次调用会重试。

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

**框架不缓存实例**：每次 `load()` / `Provider.get()` 都会重新构造 provider（或调用它的
`@ProviderFactory`），只有配置表会被加载一次并常驻。需要全局唯一实例的 provider，应让工厂返回
同一个对象（见上文“构造方式与 `@ProviderFactory`”）；`loadClass()` 和 `stateOf()` 不会实例化。

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
- 需要 JDK 17、AGP 8.0–8.13 或 9.x、`minSdk 24`。不支持 AGP 7.x。
