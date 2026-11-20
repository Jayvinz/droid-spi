# Droid-spi

English: [README.md](README.md)

**面向多 module 大型 Android 工程的编译期服务发现。** 把 service 接口声明成模块间的契约，任意模块
都可以提供实现，运行时零反射、零 APT/KSP 查找，包括位于按需下载的 dynamic feature 模块中的实现。

- 跨模块契约调用：模块之间天然依赖隔离。契约接口定义在 base / 主模块，业务模块 A 提供实现，
  业务模块 B 不依赖 A、不感知 A 的具体实现代码，也能通过本框架加载到服务实例、调用业务函数。
- 零反射：provider 全部以类字面量引用，没有 `Class.forName` 与反射构造，对移动端运行时性能友好。
- R8 友好：生成表通过类字面量和直接构造引用 provider，混淆器把它们当普通代码处理，
  无需额外的 R8 keep 配置。
- 零 APT/KSP：不需要 APT、KSP 等耗时任务，对大型项目构建友好：支持增量构建、构建缓存、
  isolated projects，完整支持多 variant 与 buildType。
- 按 key 取用多实现：一个接口可以有多个实现，查询时通过 `key` 指定所需实现。
- 绝不抛异常：不可用时返回 `null` / 空集合。

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

以一个按功能拆分模块的电商 App 为例：首页推荐流、账户中心，以及放在按需下发模块里的相机页。
仓库里 `app/` + `modules/` 的接入示例跑的就是这套代码。

大型多 module 工程天然依赖隔离：业务模块 B 需要用到业务模块 A 提供的能力，但 B 不能依赖 A，
也不该感知 A 的具体实现。在 base / 主模块定义服务的契约接口，业务模块 A 提供实现，业务模块 B
通过本框架加载到服务实例、进而调用业务函数：

```
业务模块 B（调用方）──依赖──▶ base（契约接口）◀──依赖──业务模块 A（实现）
```

B 不引用 A 的任何代码，运行时由构建期生成的表把两边连起来。契约接口放在 base / 常驻模块，
各业务模块都能编译期依赖它：

```kotlin
// :modules:core-api —— 契约。调用方与实现方都只依赖这个模块
interface HomeService {
    fun title(): String
    fun recommendations(): List<String>
}
```

实现放在它所属的模块，普通实现只需要一个注解。调用方永远不会依赖这个模块：

```kotlin
// :modules:feature-home —— 实现契约；调用方不引用这个模块
@ServiceProvider(service = HomeService::class)
class HomeServiceImpl : HomeService {
    override fun title() = "首页"
    override fun recommendations() = listOf("跑鞋限时折扣", "本周新品")
}
```

同一个接口可以挂 keyed 变体，调用方在查询时指定要哪一个：

```kotlin
@ServiceProvider(service = HomeService::class, key = "vip")
class VipHomeServiceImpl : HomeService {
    override fun title() = "首页 · VIP"
    override fun recommendations() = listOf("会员专属尖货", "免邮券")
}

@ServiceProvider(service = AccountService::class, key = "guest")
class GuestAccountServiceImpl : AccountService {
    override fun userName() = "游客"
    override fun level() = "未登录"
}
```

纯 Java 工程：`@ServiceProvider(service = HomeService.class)`。已经提供 JDK SPI 配置文件的
第三方库，把 `META-INF/services/<接口 FQN>` 放进模块的 source resources 即可接入。

### 构造方式与 `@ProviderFactory`

生成表默认用 `new Impl()` 实例化 provider。把 `@ProviderFactory` 标在一个静态无参方法上，
构造就交给 provider 类自己完成：当构造函数是 private 或需要参数时也必须这样做，比如账户服务
要从你的 app graph 里取登录态，`new Impl()` 就走不通。生成的字节码用 `INVOKESTATIC` 调用该工厂
（依然零反射），方法可以返回类自己已经持有的实例：单例、DI 容器里的对象或预先构造好的对象。
框架不缓存实例，每次 `load()` / `Provider.get()` 都会调用工厂，因此希望共享同一个对象时，
在工厂里返回缓存字段。

```kotlin
@ServiceProvider(service = AccountService::class)
class AccountServiceImpl private constructor(
    private val session: UserSession,
) : AccountService {
    override fun userName() = session.currentUser.name
    override fun level() = session.currentUser.membershipLabel

    companion object {
        // 只创建一次，交给所有调用方；框架自身不做实例缓存。
        private val shared by lazy { AccountServiceImpl(AppGraph.userSession) }

        // Kotlin 必须写成 companion object 里的 @JvmStatic；每个类最多一个工厂。
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

工厂必须是 `static`、无参，并返回 provider 类自身（不是接口），生成器发出的是
`INVOKESTATIC Impl.create()LImpl;`。构建期会校验静态形式与"每个类最多一个工厂"；无参与返回类型
两条不在构建期校验，违反时首次加载抛 `NoSuchMethodError`。`stateOf()` / `loadClass()` 仍然只用
provider 类字面量，所以可用性与 dynamic feature 行为不变。工厂抛出异常时，该次 `load()` 返回
`null`，下次调用会重试。

## 使用

```kotlin
// 可选：在 Application.onCreate 中提前初始化；不调用则在第一次查询时懒加载
ServiceLoader.initialize()

// 首页 feed：取默认实现，缺失时为 null
val home = ServiceLoader.load<HomeService>()
recycler.submitList(home?.recommendations().orEmpty())

// 会员场景：同一个接口，换一个 key
val vip = ServiceLoader.load<HomeService>("vip")

// 账户中心：登录态走默认实现，未登录降级到 guest
val account = ServiceLoader.load<AccountService>() ?: ServiceLoader.load<AccountService>("guest")
```

业务模块 B 的代码只 import 契约接口：实现来自哪个模块（业务模块 A）、那个模块装没装，都不会漏到
调用处。

API 就这么几个：`load(service, key)` 取单个实例，`loadAll(service)` 返回 `key -> 实例` 的 map，
`providers()` / `stream()` 枚举全部条目，`loadClass()` 只取 class 不触发初始化，
`stateOf()` 回答 `READY` / `MISSING` / `DEAD`。Kotlin 也可以写成上面那样的
`ServiceLoader.load<HomeService>()`。

框架不缓存实例：每次 `load()` / `Provider.get()` 都会重新构造 provider（或调用它的
`@ProviderFactory`），只有配置表会被加载一次并常驻。需要全局唯一实例的 provider，应让工厂返回
同一个对象（见上文"构造方式与 `@ProviderFactory`"）；`loadClass()` 和 `stateOf()` 不会实例化。

### Dynamic feature

相机页放在按需下发模块里，base 根本编译不到 `CameraServiceImpl`。在 app 中声明模块
（`dynamicFeatures += setOf(":modules:feature_camera")`）并对其 apply 插件。页面跳转由实现自己完成，
base 永远不需要知道 `CameraActivity` 的类名：

```kotlin
// :modules:feature_camera —— 契约调用在这个模块未安装时也能成立
@ServiceProvider(service = CameraService::class)
class CameraServiceImpl : CameraService {
    override fun open(context: Context) {
        context.startActivity(Intent(context, CameraActivity::class.java))
    }
}
```

相机按钮只需要探测一下：

```kotlin
fun onCameraClick(context: Context) {
    val camera = ServiceLoader.load(CameraService::class.java)
    if (camera != null) {
        camera.open(context)          // 已安装：实现自己打开页面
    } else {
        installFeatureCamera()        // 申请 "feature_camera" split，例如走 Play Feature Delivery
    }
}
```

安装前 provider 为 `MISSING`；安装完成后下一次 `load()` 就返回实例。没有安装监听器，也不需要刷新。

### flavor 与 buildType 变体

每个 variant 有自己的一张表，实现可以跟着真实工程已在用的源集走：`free` / `paid` 两个 flavor
各自在 `src/free`、`src/paid` 里提供 `ChannelService`，只在调试用的服务放在 `app/src/debug`，
release 表里干脆没有这条记录。`paid` 包只会看到 `paid` 的实现，不需要任何接线。

### 多个默认实现

空 key 可以由多个实现共享。`load()` 返回第一个可用实现，顺序为
`(service, key, provider FQN)`；`providers()` 枚举全部。非空 key 仍然必须唯一，冲突会导致
构建失败。

## 传统做法

多模块工程都会撞上同一堵墙：业务模块 B 需要用业务模块 A 提供的行为，但 B 不能依赖 A。常见的几种
解法各有各的负担。

最直接的是字符串类名加反射。调用方编译不到实现，类名只能以字符串传递：

```kotlin
val clazz = Class.forName("com.example.feature.home.HomeServiceImpl")
val home = clazz.getDeclaredConstructor().newInstance() as HomeService
```

改个类名不会有任何编译错误，字符串悄悄失效；R8 需要 `-keep` 规则才能留住这个类；
模块缺失时拿到的是 `ClassNotFoundException`。

大型工程更常见的是手写注册表。每个 feature 把自己的实现塞进共享的 map：

```kotlin
object ServiceRegistry {
    private val impls = mutableMapOf<Class<*>, Any>()
    fun <T : Any> put(service: Class<T>, impl: T) { impls[service] = impl }
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(service: Class<T>): T? = impls[service] as? T
}

// 每个 feature 的初始化代码里
ServiceRegistry.put(HomeService::class.java, HomeServiceImpl())
```

没有反射了，但每个 feature 都得记得注册，还要有人按正确顺序触发这些初始化；key 变体、
buildType 差异、动态模块，最后都变成手写分支。

接口如果本来就按 JDK SPI 约定声明，`java.util.ServiceLoader`（或 Google 的 AutoService）
看起来是现成答案：

```kotlin
val home = java.util.ServiceLoader.load(HomeService::class.java).first()
```

它的底层仍是拿 `META-INF/services` 文本做反射，改名和 R8 的问题一个不少；没有 `key`，
也不感知 variant；provider 的类缺失时抛 `ServiceConfigurationError` 而不是返回 null。
AutoService 免去了手写描述文件，代价是 APT。

Hilt / Koin 解决的是闭合图里的依赖装配，形状并不匹配这个问题：还没安装的动态模块不是一个
能参与编译期依赖图的节点，而让 KSP/KAPT 拖慢大型构建正是本项目要避开的成本。

|  | 运行时反射 | key 多实现 | 动态模块 | 构建成本 |
|---|---|---|---|---|
| 字符串类名 | `Class.forName` | 手写 | 手写 | 无 |
| 手写注册表 | 无 | 手写 | 安装回调 + 手动刷新 | 无 |
| JDK `ServiceLoader` / AutoService | 反射构造 | 无 | 抛 `ServiceConfigurationError` | AutoService 走 APT |
| DI（Hilt / Koin） | 无 | multibinding | 不在编译期图内 | Hilt 走 KSP / KAPT |
| **Droid-spi** | 无 | `key` + 按 variant 成表 | 下一次 `load()` 即可用 | 一次扫描，无 APT |

Droid-spi 保留 `ServiceLoader` 的调用形状，把表前移到构建期：不可用的 provider 返回 `null`，
每个 variant 一张表，动态模块安装完成后下一次 `load()` 自动可见，不需要刷新。在大型多模块工程里，
这意味着各团队围绕一份共同的契约并行开发：调用方留在接口上，实现留在自己的模块里，两者由构建
接线。生成表用类字面量引用 provider，R8 把它们当普通代码处理，无需额外的 R8 keep 配置。

## 项目结构

库本体只有三个模块：

| 路径 | 职责 |
|---|---|
| `plugin/` | Gradle 插件。按 variant 扫描源码、汇总清单，生成 `ServiceConfiguration` 字节码表。 |
| `annotation/` | `@ServiceProvider` 与 `@ProviderFactory`，CLASS 保留，不进运行时。 |
| `runtime/` | `ServiceLoader` 门面与注册表。查生成表取 provider，全程零反射。 |

其余部分都是接入示例：`app/` 与 `modules/` 组成两个 flavor × 两个 buildType 的 demo，外加按需下发的
相机模块，带一个自检界面，在真机上逐条验证整张 provider 表。

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
- 需要 JDK 17、AGP 8.0-8.13 或 9.x、`minSdk 24`。不支持 AGP 7.x。
