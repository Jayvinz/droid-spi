# Android 编译期服务发现——不依赖反射，也不用 KSP

*认识 Droid-spi：一个小巧的 Gradle 插件，为 Java/Kotlin 模块带来 JDK `ServiceLoader` 的使用体验，
即使 provider 位于动态功能模块中也能正常工作，而且不会给运行时热路径增加任何开销。*

---

如果你维护一个大型 Android 应用，迟早会撞上下面某一堵墙：

- base 模块需要调用某个功能模块，但依赖方向是反的。
- 实现位于按需下载的动态功能模块中，因此在用户安装之前，这个类在进程里根本不存在。
- 你想要随变体变化的实现（`free` 对 `paid`、`debug` 对 `release`），又不想在半个应用里铺满 `if`。

经典方案是反射（`Class.forName` 加注册表）、DI 图，或 Google 的 AutoService。它们各有代价：
反射是字符串类型的，容易写错且难以重构；DI 图在编译期就是闭合的，无法表达"这个类还没安装"；
AutoService 生成 `META-INF/services` 文件，而 Android 运行时无法直接从 AAR 中读取它，需要额外处理，
并且它既没有变体概念，也没有动态下发的方案。

`Droid-spi` 沿用 ServiceLoader 的思路，把 provider 表前移到构建期。

## 使用起来是什么样

在 base 模块中声明接口：

```kotlin
interface HomeService {
    fun title(): String
}
```

在实现所在的任意模块中声明 provider：

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

在 `Application.onCreate` 中提前初始化（可选）：

```kotlin
ServiceLoader.initialize()
```

配置表本来就会在第一次查询时懒加载；这里只是让开销确定，并把“生成类缺失”暴露在启动阶段。

在需要的地方查找：

```kotlin
val home = ServiceLoader.load(HomeService::class.java)          // HomeServiceImpl 或 null
val vip  = ServiceLoader.load(HomeService::class.java, "vip")   // VipHomeServiceImpl 或 null
val all  = ServiceLoader.loadAll(HomeService::class.java)       // key -> 实例
```

常见场景下，这就是你需要掌握的全部 API。注意其中缺少了什么：没有 `@Inject`，没有 component，
没有代码生成插件，也没有字符串形式的类名。

## 为什么 provider 可以不存在

这正是它对 App Bundle 有用的原因。

动态功能模块依赖 base 应用，而不是反过来。base 无法针对 `CameraServiceImpl` 编译，因为这个类
不在它的编译类路径上。那么 base 中的代码要怎么调用它？

`Droid-spi` 在构建期化解了这个矛盾：

1. 每个模块变体扫描自己的源码，写出一份小清单。
2. 应用变体聚合所有兼容的清单，生成一个固定全限定名的类 `ServiceConfiguration`。
3. 这个类通过**写在方法体内的类字面量**返回 provider 的 class。
4. 运行时 JVM 惰性解析这些字面量。功能模块尚未安装时，查找会抛出 `NoClassDefFoundError`；
   运行时会捕获它并报告 `MISSING`。
5. 模块安装完成后，下一次 `load()` 就能成功解析该字面量。既没有 refresh 调用，也不缓存否定结果。

关键的细节是"没有静态初始化块"。如果把 provider 类字面量放进 `<clinit>`，那么第一次引用未安装的
模块时，JVM 会把整个配置类标记为 erroneous——永久破坏表中所有其他 provider。因此生成器刻意不生成
`<clinit>`，每个字面量都位于方法体内。

## 变体是一等公民

`freeDebug` 和 `paidDebug` 编译的是不同的源集。如果按"构建类型"聚合，就会在不知不觉中把某个
flavor 的 provider 给到另一个 flavor，因此聚合是按**变体**进行的：

- `freeDebug` 拥有自己的扫描任务、自己的清单、自己的生成类。
- 生成类被追加到该变体的 `CLASSES` artifact 上，打包时会自动选中正确的那一份。
- `paidDebug` 完全独立。

仓库中的 demo 包含两个 flavor × 两个构建类型，并有一个自检界面，在真机上验证全部 12 条不变量，
包括"仅 debug 存在的 provider 只出现在 debug 构建中"以及"flavor 专属 provider 在 free 与 paid
之间不同"。

## 第三方 `META-INF/services` 怎么办

如果某个库已经按 JDK SPI 规范提供了描述文件，你不需要 fork 它。把
`META-INF/services/<接口 FQN>` 文件放到模块的源码 resources 目录下即可。扫描器会读取它，并把每个
条目当作注解声明处理，区别在于条目映射到默认 key，且要求实现类有 public 无参构造函数。demo 中的
`:modules:core-common` 就是这样接入一个遗留 provider 的。

## 安装并试用

```kotlin
plugins {
    id("top.jayvin.droidspi") version "1.0.0-alpha02"
}

dependencies {
    implementation("top.jayvin.droidspi:annotation:1.0.0-alpha02")
    implementation("top.jayvin.droidspi:runtime:1.0.0-alpha02")
}
```

插件发布在 Gradle Plugin Portal，库发布在 Maven Central，使用标准仓库即可。源码仓库：
[Jayvinz/droid-spi](https://github.com/Jayvinz/droid-spi)。

环境要求：JDK 17、AGP 8.x、`minSdk 24`。

## 什么时候不该用它

- 你的模块全都是编译期依赖、并且始终安装。那就用 Hilt/Dagger——你能获得作用域、生命周期和
  构造函数注入，而这些并不是本项目试图提供的。
- 你需要在运行时注册任意类（例如从磁盘加载插件）。本项目是编译期表，每个 provider 都必须对构建
  可见。

如果你需要的是"发现一个此刻可能还不在进程里的 provider"——动态功能模块、大型模块化应用、
随变体变化的实现——那正是它填补的空白。

---

*下一篇：[Droid-spi 的内部实现](02-how-droid-spi-works.zh-CN.md)——artifact 传输、字节码生成、
JVM 类初始化语义，以及让整条流水线保持正确的缓存与隔离规则。*
