# droid-spi

**面向多 module 大型 Android 工程的编译期服务发现。**

English: [README.md](README.md) · `1.0.0` · JDK 17 · AGP 8.0–9.5 · minSdk 24

把 service 接口声明成模块之间的契约，任意模块提供实现，运行时按需查找实例。全程零反射、零注解处理；
即使实现位于按需下发的 dynamic feature 模块，装好后下一次查询即可用。

- **跨模块契约调用。** 契约接口定义在 base 模块，业务模块 A 提供实现，业务模块 B 不依赖 A、
  不感知 A 的具体代码，也能调用它的能力。
- **零反射。** provider 与生成的表之间通过类字面量和直接构造互指，对移动端运行时更友好。
- **R8 友好。** 混淆器把生成的表当普通代码处理，无需为它配置 `-keep`。
- **零 APT/KSP。** 没有注解处理轮次，大型工程构建不受拖累；支持增量构建、构建缓存、
  isolated projects，以及全部 variant / buildType。
- **按 key 取用多实现。** 一个接口可以有多个实现，查询时用 `key` 指定所需实现。
- **绝不抛异常。** 不可用时返回 `null` 或空集合。

## 目录

- [为什么需要 droid-spi](#为什么需要-droid-spi)
- [快速上手](#快速上手)
- [环境要求](#环境要求)
- [集成](#集成)
- [声明 provider](#声明-provider)
  - [契约接口](#契约接口)
  - [提供实现](#提供实现)
  - [用 `@ProviderFactory` 接管构造](#用-providerfactory-接管构造)
  - [标准 `META-INF/services` 描述文件](#标准-meta-infservices-描述文件)
- [使用服务](#使用服务)
  - [API 一览](#api-一览)
  - [生命周期、缓存与失败语义](#生命周期缓存与失败语义)
- [dynamic feature 模块](#dynamic-feature-模块)
- [flavor 与 buildType](#flavor-与-buildtype)
- [多个默认实现](#多个默认实现)
- [Gradle DSL](#gradle-dsl)
- [实现原理](#实现原理)
- [与其他方案对比](#与其他方案对比)
- [工程结构](#工程结构)
- [常见问题排查](#常见问题排查)
- [已知限制](#已知限制)
- [延伸阅读](#延伸阅读)
- [参与开发](#参与开发)
- [许可证](#许可证)

## 为什么需要 droid-spi

大型多 module 工程天然依赖隔离：业务模块 B 需要业务模块 A 提供的能力，但 B 不能依赖 A，
也不该感知 A 的具体实现。在 base 模块定义 service 接口作为两者之间的契约，A 提供实现，
B 通过框架加载实例、调用业务函数，运行时由构建期生成的表把两边连起来。

```
业务模块 B（调用方）──依赖──▶ base（契约接口）◀──依赖──业务模块 A（实现）
```

B 不引用 A 的任何代码。哪个模块提供了实现、该模块是否已安装，都不会泄漏到调用方。

## 快速上手

**1. 给 application 模块和每个声明 provider 的模块 apply 插件：**

```kotlin
// 模块 build.gradle.kts
plugins {
    // 支持的模块类型：application、library、dynamic-feature
    id("top.jayvin.droidspi") version "1.0.0"
}
```

**2. 在 base 模块声明契约，在任意业务模块提供实现：**

```kotlin
// :modules:base —— 契约。调用方与实现方都只依赖这个模块
interface FeedSource {
    fun load(page: Int, pageSize: Int): FeedPage
}

// :modules:community —— 实现契约；调用方不引用这个模块
@ServiceProvider(service = FeedSource::class)
class RecommendFeedSource : FeedSource {
    override fun load(page: Int, pageSize: Int) = pagedIds(
        CommunityRepository.recommend(), page, pageSize,
    )
}
```

**3. 在需要的地方取用。** 调用方只 import 接口：

```kotlin
val feed = ServiceLoader.load<FeedSource>()
recycler.submitList(feed?.load(page = 0, pageSize = 30)?.videoIds.orEmpty())
```

接入到此为止。仓库配置、依赖坐标和完整 API 见下文。

## 环境要求

| 项目 | 要求 |
|---|---|
| JDK | 17 |
| Android Gradle Plugin | 8.0–8.13、9.0–9.5（不支持 AGP 7.x） |
| Gradle | 与所用 AGP 匹配的版本（本仓库为 9.6） |
| `minSdk` | 24 |
| 模块类型 | `com.android.application`、`com.android.library`、`com.android.dynamic-feature` |
| provider 语言 | Kotlin 或 Java；不需要 KAPT/KSP，也没有暴露给构建脚本的代码生成步骤 |

AGP 9 起内置 Kotlin 支持：使用 AGP 9 的模块**不要**再 apply `org.jetbrains.kotlin.android`，
否则构建会失败。模块只需配置 `compileOptions`，内置 Kotlin 会据此推导 `jvmTarget`。

## 集成

插件来自 Gradle Plugin Portal，库来自 Maven Central，不需要额外配置仓库或 token。

```kotlin
// settings.gradle.kts —— 如果项目里还没有声明
pluginManagement {
    repositories {
        gradlePluginPortal()   // 插件的仓库
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()         // annotation / runtime 的仓库
    }
}
```

| 产物 | 坐标 | 来源 | 谁需要 |
|---|---|---|---|
| Gradle 插件 | `top.jayvin.droidspi`（插件 id） | Plugin Portal | app 及每个 provider 模块 |
| 注解 | `top.jayvin.droidspi:annotation` | Maven Central | provider 模块 |
| 运行时 | `top.jayvin.droidspi:runtime` | Maven Central | application 模块，或其他调用 `ServiceLoader` 的模块 |

```kotlin
// 模块 build.gradle.kts
dependencies {
    // provider 模块只需要 annotation
    implementation("top.jayvin.droidspi:annotation:1.0.0")
}

// app/build.gradle.kts
dependencies {
    implementation("top.jayvin.droidspi:annotation:1.0.0")
    implementation("top.jayvin.droidspi:runtime:1.0.0")
}
```

`annotation` 与 `runtime` 均输出 Kotlin 2.0 metadata，Kotlin 1.8+ 的使用方都能编译。
插件、`annotation`、`runtime` 三者请保持同一版本。

## 声明 provider

### 契约接口

service 接口是模块之间的接缝，放在调用方与实现方都已经依赖的 base / 常驻模块里：

```kotlin
// :modules:base
interface FeedSource {
    fun load(page: Int, pageSize: Int): FeedPage
}
```

必须是接口，且不能来自 dynamic feature 模块，原因见[已知限制](#已知限制)。

### 提供实现

普通实现只需要一个注解。该模块 apply 插件、依赖 `annotation`，调用方不依赖它：

```kotlin
// :modules:community
@ServiceProvider(service = FeedSource::class)
class RecommendFeedSource : FeedSource {
    override fun load(page: Int, pageSize: Int) = pagedIds(
        CommunityRepository.recommend(), page, pageSize,
    )
}
```

同一个接口可以有带 key 的变体，调用方在查询时选择需要的那一个：

```kotlin
@ServiceProvider(service = FeedSource::class, key = "following")
class FollowingFeedSource : FeedSource {
    override fun load(page: Int, pageSize: Int) = pagedIds(
        CommunityRepository.following(), page, pageSize,
    )
}
```

Kotlin 只接受 Java 注解的具名参数，因此统一写成 `@ServiceProvider(service = FeedSource::class)`。
纯 Java 工程用类字面量：`@ServiceProvider(service = FeedSource.class)`。

### 用 `@ProviderFactory` 接管构造

生成的表通常用 `new Impl()` 构造 provider。给一个 static、无参的方法加上 `@ProviderFactory`，
就把构造交给 provider 类自己。构造函数私有或带参数时这是必需的：需要从 App 容器里取
session 的账户服务，没法用 `new Impl()` 构造。

生成的字节码通过 `INVOKESTATIC` 调用工厂方法（依然零反射），方法可以返回类已经持有的任何实例：
单例、依赖注入容器中的对象，或预先构造好的对象。框架不缓存实例，因此每次 `load()` /
`Provider.get()` 都会调用工厂；需要共享同一个对象时，返回缓存的字段即可。

```kotlin
@ServiceProvider(service = AccountService::class)
class AccountServiceImpl private constructor(
    private val session: UserSession,
) : AccountService {
    override fun userName() = session.currentUser.name
    override fun level() = session.currentUser.membershipLabel

    companion object {
        // 只创建一次并交给所有调用方；框架本身不缓存
        private val shared by lazy { AccountServiceImpl(AppGraph.userSession) }

        // Kotlin 需要 companion object + @JvmStatic；每个类最多一个工厂
        @ProviderFactory @JvmStatic
        fun create(): AccountServiceImpl = shared
    }
}
```

```java
@ServiceProvider(service = AccountService.class)
public class AccountServiceImpl implements AccountService {
    private static final AccountServiceImpl INSTANCE = new AccountServiceImpl();

    @ProviderFactory
    public static AccountServiceImpl create() { return INSTANCE; }
}
```

工厂方法必须是 `static`、无参，且返回 provider 类本身（不是接口），因为生成器要拼出
`INVOKESTATIC Impl.create()LImpl;`。构建期会校验 static 形式与「每类最多一个工厂」；
无参和返回类型不做校验，写错会在首次加载时以 `NoSuchMethodError` 暴露。
工厂方法抛异常时该次 `load()` 返回 `null`，下次调用仍会重试。

示例中的 `LocalAccountService` 就是这种写法：私有构造函数接收 application context，
`create()` 返回一个懒创建的实例。

### 标准 `META-INF/services` 描述文件

已经按 JDK SPI 约定提供描述文件的第三方库，不必改代码即可接入：把
`META-INF/services/<接口全限定名>` 放在模块的源码资源目录（通常是 `src/main/resources/`）。
插件只读取文本描述文件，不解压依赖 jar、不扫描其中的类。DroidReel 示例的媒体目录就是这样声明的。

## 使用服务

```kotlin
// 可选：在 Application.onCreate 里提前初始化；不调用则在首次查询时惰性初始化
ServiceLoader.initialize()

// 推荐流：默认实现，缺失时返回 null
val feed = ServiceLoader.load<FeedSource>()
recycler.submitList(feed?.load(page = 0, pageSize = 30)?.videoIds.orEmpty())

// 关注流：同一接口，不同 key
val following = ServiceLoader.load<FeedSource>("following")

// 账户中心：唯一实现，账户模块不存在时返回 null
val account = ServiceLoader.load<AccountService>()
```

### API 一览

`ServiceLoader` 尽量对齐 `java.util.ServiceLoader`。Java 调用方传类字面量（所有方法都是
`@JvmStatic`），Kotlin 另有 inline 的 `load<T>()` 形式。

| 调用 | 返回 | 说明 |
|---|---|---|
| `initialize()` | `Unit` | 提前加载表；可选、幂等 |
| `isInitialized()` | `Boolean` | 表是否已加载（含空表兜底） |
| `load(service[, key])` / `load<T>([key])` | `T?` | 构造 provider 实例；不可用时为 `null` |
| `providerOf(service[, key])` | `Provider<T>?` | 只给句柄，不构造实例 |
| `findFirst(service)` | `Provider<T>?` | 优先默认 key，其次任意可用实现 |
| `providers(service)` / `stream(service)` | `List<Provider<T>>` / `Sequence<Provider<T>>` | 枚举全部可用实现，含多个默认实现 |
| `loadClass(service[, key])` | `Class<out T>?` | 只取类，不触发其 `<clinit>` |
| `loadAll(service)` | `Map<String, T>` | `key -> 实例`，每个 key 一项 |
| `loadAllClasses(service)` | `List<Class<out T>>` | 全部可用的 provider 类 |
| `stateOf(service[, key])` | `ProviderState?` | `READY` / `MISSING` / `DEAD`；未注册时为 `null` |
| `snapshot()` | `List<Triple<String, String, ProviderState>>` | 调试用全表视图，每次调用重新探测 |
| `DEFAULT_KEY` | `""` | 默认实现的 key |

`Provider<S>` 句柄提供 `type()`（实现类）、`get()`（实例）与 `state()`。`stream()` / `findFirst()`
返回的句柄在创建时处于 `READY`，若其模块随后被卸载，`get()` 仍可能返回 `null`。

### 生命周期、缓存与失败语义

- **不缓存实例。** 每次 `load()` / `Provider.get()` 都会重新构造 provider 或调用它的
  `@ProviderFactory`。只有配置表加载一次并常驻。
- **表是惰性加载的**：首次查询时线程安全地加载（双重检查），或通过 `initialize()` 提前加载。
  `ServiceConfiguration` 缺失或非法时只记录日志并降级为空表，不会抛出。
- **`DEAD` 按表项缓存**：`<clinit>` 失败的类已被 JVM 标记为错误状态，进程内不可恢复。
  `MISSING` 从不缓存，因此模块装好后下一次调用即可用，不需要刷新、不需要安装监听。
- **绝不抛异常。** 不可用是正常状态，返回 `null` 或空集合。
- **默认 key 兜底。** 默认 key 无可用实现、且该服务恰好只有一个可用的非默认 key 时，
  `load(service)` 会返回该实现。有两个及以上可用 key 时不做兜底，需要显式指定 key。
- **顺序。** 表项按 `(service, key, provider FQN)` 排序，取第一个可用项。
  需要全部实现时用 `loadAll` / `providers`。

## dynamic feature 模块

按需下发模块无法被编译期引用，base app 也就引用不到 `ProductionEntryImpl`。在 app 里声明该模块
（`dynamicFeatures += setOf(":modules:production")`）并给它 apply 插件，由实现自己打开页面，
调用方始终不需要知道 `ProductionHubActivity` 的类名：

```kotlin
// :modules:production —— 即使该模块尚未安装，契约调用也能编译通过
@ServiceProvider(service = ProductionEntry::class)
class ProductionEntryImpl : ProductionEntry {
    override fun openCreate(context: Context) {
        context.startActivity(Intent(context, ProductionHubActivity::class.java))
    }
}
```

「发布」按钮只需要探测一次：

```kotlin
fun onCreateClick(context: Context) {
    val production = ServiceLoader.load(ProductionEntry::class.java)
    if (production != null) {
        production.openCreate(context)   // 已安装：实现自己打开页面
    } else {
        installProduction()              // 通过 Play Feature Delivery 请求 "production" split
    }
}
```

安装前 provider 状态是 `MISSING`，安装后下一次 `load()` 就能拿到实例。不需要安装监听，
也不需要刷新。

## flavor 与 buildType

每个 variant 都有自己的表，因此实现可以跟着工程已有的 source set 走。示例的 `free` / `vip`
两个 flavor 分别从 `src/free` 和 `src/vip` 提供 `ChannelService`，`app/src/debug` 下的
`DemoInfoService` 在 release 表里则根本没有条目。`vip` 包只会看到 `vip` 的实现，无需任何额外配置。

聚合是按 variant 而不是按 buildType 进行的：`freeDebug` 与 `vipDebug` 编译不相交的 flavor 源码，
各自拥有独立的表。

## 多个默认实现

空 key 可以由多个实现共享。`load()` 按 `(service, key, provider FQN)` 排序返回第一个可用的，
`providers()` 枚举全部。非空 key 必须全局唯一，重复会导致构建失败。

## Gradle DSL

插件在它 apply 的每个模块里注册 `spi { }` 扩展：

```kotlin
spi {
    // false：该模块既不扫描也不生成。排查构建问题时可用
    enabled = true

    // false：忽略本模块源码目录下的 META-INF/services/*.txt
    scanMetaInfServices = true
}
```

## 实现原理

1. **扫描：按模块、按 variant。** 插件注册 `{variantName}ScanProviders`，从 `src/main` 及该
   variant 的 buildType / flavor 叠加目录中读取 `@ServiceProvider` 注解和 `META-INF/services`
   文件，输出 `build/generated/spi/{variantName}/services.txt`。
2. **合并：仅 application 模块。** `{variantName}GenerateServiceConfiguration` 收集所有
   兼容模块 variant 的描述文件，校验 key 唯一性，用 ASM 生成
   `top.jayvin.android.spi.generated.ServiceConfiguration`。
3. **解析：运行时。** `ServiceLoader` 在首次查询时构造这个类，之后按需从可按下标寻址的表里探测 provider。

描述文件通过 Gradle 的 variant-aware 产物解析传递（`{variant}RuntimeElements` 上的
`spi-manifest` 产物），而不是跨项目收集器，因此 scan → generate 的依赖边在配置缓存复用和
isolated projects 下都不会丢。

生成的类刻意**没有静态初始化块**：provider 类字面量只出现在方法体内。否则未安装的 dynamic
module 会让配置类被 JVM 标记为错误，整个表在进程内永久失效。service 接口必须放在 base 模块，
出于同样的原因。

## 与其他方案对比

多 module 工程都会撞上同一堵墙，常见做法各有代价：

- **字符串类名 + 反射**（`Class.forName`）：改名后静默失效，需要 R8 `-keep`，模块缺失时抛
  `ClassNotFoundException`。
- **手写注册表**：没有反射，但每个模块都得记得注册、初始化顺序需要有人负责，按 key 取用、
  buildType、dynamic module 全都要手写分支。
- **JDK `ServiceLoader` / AutoService**：反射读取 `META-INF/services`，改名与 R8 的问题依旧；
  没有 `key`、不感知 variant，provider 缺失时抛 `ServiceConfigurationError`；AutoService 用 APT
  换掉了描述文件。
- **Hilt / Koin**：解决的是封闭依赖图内的装配，形状不合适：尚未安装的 dynamic module
  不是图里能编译到的节点。

|  | 反射 | 按 key 多实现 | 按需下发模块 | 构建开销 |
|---|---|---|---|---|
| 字符串类名 | `Class.forName` | 手写 | 手写 | 无 |
| 手写注册表 | 无 | 手写 | 安装回调 + 手动刷新 | 无 |
| JDK `ServiceLoader` / AutoService | 反射构造 | 无 | 抛 `ServiceConfigurationError` | APT（AutoService） |
| DI（Hilt / Koin） | 无 | multibindings | 在编译期图之外 | KSP / KAPT（Hilt） |
| **droid-spi** | 无 | `key`，每 variant 独立表 | 下一次 `load()` 即可用 | 一次扫描，无 APT |

droid-spi 保留 `ServiceLoader` 的调用形态，把表挪到构建期完成。

## 工程结构

框架本体是三个发布模块，外加一个构建期占位模块：

| 路径 | 作用 |
|---|---|
| `plugin/` | Gradle 插件。扫描每个 variant 的源码、合并描述文件、生成 `ServiceConfiguration` 字节码表。 |
| `annotation/` | `@ServiceProvider` 与 `@ProviderFactory`。`CLASS` retention，零依赖，注解不进入运行时。 |
| `runtime/` | `ServiceLoader` 门面与注册表。在生成的表里查找 provider，零反射。 |
| `configuration-stub/` | 生成类名的 `compileOnly` 占位。不发布、不打包。 |

其余都是集成示例：`app/` 是 **DroidReel**，一个 Compose 短视频 demo，两套 `free` / `vip`
flavor × 两种 buildType；`modules/` 提供它的契约、账户、社区和按需下发的创作模块。
个人页的 **SPI status** 弹窗会列出当前生成的表，方便真机核对。`compat/` 是 AGP 8.0 / 8.7
的兼容性验证工程。构建与运行说明见 [docs/example-app.md](docs/example-app.md)。

## 常见问题排查

| 现象 | 原因 | 处理 |
|---|---|---|
| 刚 clone 后 `:app` 解析不到插件 | `local-repo/` 被 gitignore，内容为空 | `./gradlew :annotation:publish :runtime:publish :plugin:publish --configure-on-demand` |
| 改了插件但 demo 行为没变 | demo 消费的是已发布产物，不是项目依赖 | 重新发布后再构建；若仍是旧字节码，删掉 `{Variant}GenerateServiceConfiguration` 中间目录 |
| 构建报 key 重复 | 非空 `key` 必须在单个 service 内唯一 | 改掉其中一个 key，或去掉 key 用默认槽位 |
| 构建提示需要显式 import | `import a.b.*` 无法解析出 service 类型 | 显式 import 该类型，或直接写全限定名 |
| 首次加载抛 `NoSuchMethodError` | `@ProviderFactory` 不是 static/无参，或返回了接口类型 | 改为 `static` / `@JvmStatic`、无参、返回实现类 |
| 运行时某个 provider 始终不出现 | 该模块没 apply 插件，或 flavor dimension 与 app 不匹配 | 给每个 provider 模块 apply 插件；与 app 保持相同的 flavor dimension |
| 开混淆后出现 `NoClassDefFoundError` 或缺失类警告 | 未安装模块的 provider 在压缩期不存在 | 为这些模块加 `-dontwarn` |
| service 接口放在了 dynamic feature 里 | 接口必须常驻，表才能解析 | 把接口移到 base 模块 |

怀疑是框架缺陷时，先跑 [docs/droid-spi-design.md](docs/droid-spi-design.md) §8.1 的验证矩阵。

## 已知限制

- service 接口必须位于 base / 常驻模块，不能放在 dynamic feature 里。
- 只支持接口：不能用嵌套类、`typealias` 或抽象类作为 service 接口。
- 非空 key 在整次构建内对同一 service 唯一。
- 不支持 AGP 7.x；AGP 8.0–8.13 与 9.0–9.5 共用一个插件产物。

## 延伸阅读

- 设计与实现：[docs/droid-spi-design.zh-CN.md](docs/droid-spi-design.zh-CN.md)
  （[English](docs/droid-spi-design.md)）
- 上手指南：
  [中文](docs/articles/01-droid-spi-getting-started.zh-CN.md) ·
  [English](docs/articles/01-droid-spi-getting-started.en.md)
- 插件内部实现：
  [中文](docs/articles/02-droid-spi-plugin-internals.zh-CN.md) ·
  [English](docs/articles/02-droid-spi-plugin-internals.en.md)
- 早期长文系列：[认识 droid-spi](docs/articles/01-introducing-droid-spi.zh-CN.md)
  （[English](docs/articles/01-introducing-droid-spi.md)）·
  [内部实现剖析](docs/articles/02-how-droid-spi-works.zh-CN.md)
  （[English](docs/articles/02-how-droid-spi-works.md)）
- 示例工程：[docs/example-app.md](docs/example-app.md)

## 参与开发

```bash
# 改动 annotation / runtime / plugin 后先发布到 local-repo：
# app 与 modules 消费的是已发布产物，不是项目依赖
./gradlew :annotation:publish :runtime:publish :plugin:publish

./gradlew :app:assembleFreeDebug          # 单个 variant（flavor：free/vip；type：debug/release）
./gradlew :runtime:testDebugUnitTest      # runtime 单元测试（纯 JVM，无需设备）
./gradlew :plugin:test                    # 描述文件合并与 key 校验测试
```

Windows 下使用 `gradlew.bat`。查看生成的表：

```bash
javap -p -c app/build/intermediates/classes/freeDebug/FreeDebugGenerateServiceConfiguration/top/jayvin/android/spi/generated/ServiceConfiguration.class
```

发版通过推送 `v*` tag 触发：工作流会核对 tag 与 `gradle.properties` 中的 `spiVersion`，
把 `annotation` / `runtime` 发布到 Maven Central、插件发布到 Gradle Plugin Portal。
完整发布流程与兼容性说明见 [CLAUDE.md](CLAUDE.md)。

## 许可证

Apache License 2.0，版权归 Jayvin（2026）。全文见 [LICENSE](LICENSE)。
