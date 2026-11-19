# droid-spi 的内部实现：artifact 传输、ASM 与 JVM 类初始化

*深入 Android 编译期 SPI 背后的流水线：为什么清单要以 Gradle artifact 的形式传输，为什么生成的类
不能有静态初始化块，以及配置缓存和隔离项目如何塑造了这套设计。*

---

[第一篇](01-introducing-droid-spi.zh-CN.md)介绍了 `droid-spi` 能做什么。这一篇讲它是怎么实现的，
以及为什么好几种看似显然的实现方式其实是错的。

项目包含三个模块：`annotation`（两个纯 Java 注解）、`runtime`（`ServiceLoader` 门面与
`ProviderRegistry`）、`plugin`（Gradle 插件、扫描任务和 ASM 生成器）。第四个模块
`configuration-stub` 编译一个占位类，让 runtime 能够针对一个尚不存在的类编写直接的 `new`。

## 阶段 1：按模块、按变体扫描

对每个 Android 模块和每个变体，插件都注册一个 `{variant}ScanProviders` 任务。它的输入是该变体
声明的源码目录，来自 `variant.sources`（`java.static`、`kotlin.static`，以及启用
`META-INF/services` 扫描时的 `resources.static`）。这个细节很重要：直接使用 `src/main` 会漏掉
`src/debug`、`src/free`、`src/paid` 以及其他所有覆盖层。

扫描器刻意没有做成 AST 解析器。它先剥离注释、屏蔽字符串内容，然后匹配注解，并通过括号深度找到
注解所在的类。这样插件就不依赖 Kotlin 编译器内部结构，并且对 Java 和 Kotlin 源码的行为完全一致。

输出是 `build/generated/spi/{variant}/services.txt`，每个条目一行：

```
service <TAB> provider <TAB> key <TAB> factory
```

key 为空表示默认实现；factory 为空表示"调用无参构造函数"。

## 阶段 2：清单以 artifact 传输，而不是跨项目模型

最初的实现使用根项目扩展加一个共享 build service：每个模块把清单发布到中央 Map，app 在配置阶段
结束时读取它。它在热构建中工作正常，但在配置缓存复用、尤其是隔离项目下会失效。原因很微妙：

- 由一个已经配置完成的工程产生的 `Provider<RegularFile>`，如果被另一个工程持有为任务输入，在配置
  缓存复用时会被 Gradle 序列化成普通文件路径。
- 于是扫描任务从任务图中消失，而生成任务仍然读取旧文件。构建"成功"了，但用的是过期数据。

修复方案是改用 Gradle 已经建模正确的那套机制：**variant-aware artifact 解析**。

- 每个模块变体把它的 `services.txt` 以自定义 artifact 类型 `spi-manifest` 附加到
  `{variant}RuntimeElements` 上。
- 应用的生成任务对 `variant.runtimeConfiguration` 取 `artifactView`，并按该 artifact 类型过滤。
  解析是传递且惰性的；没有应用插件的模块自然不贡献任何内容。
- 动态功能模块与 app 之间没有 runtime 依赖边，因此 app 为自己 `dynamicFeatures` DSL 中的每个路径
  添加依赖，并对该功能模块的 runtime elements 取同样的过滤视图。
- 生成任务通过 `toAppend(ScopedArtifact.CLASSES)` 直接把 class 文件写入自己的变体，且不读取
  `CLASSES` 输入，因此任务图不会成环。

这比共享 service 的代码更多，但它是唯一一种能在配置缓存复用和隔离项目下保持 scan → generate
依赖边完整的形态。

## 阶段 3：谨慎地生成字节码

生成器输出一个固定全限定名的类
`top.jayvin.android.spi.generated.ServiceConfiguration`，实现 `ProviderConfiguration`：

```kotlin
interface ProviderConfiguration {
    fun services(): Array<Class<*>>
    fun keys(): Array<String>
    fun providerOf(service: Class<*>, key: String): Class<*>?
    fun newProvider(service: Class<*>, key: String): Any?
}
```

`services()` 与 `keys()` 必须**按下标对齐，每个条目一个元素**。如果 `HomeService` 有两个 key，
接口就会出现两次。这听起来冗余，但正是它让运行时能够按 service 分组、按下标读取 key；如果启动时
每个接口只返回一次，就会得到 `Broken provider configuration: services=X keys=Y`。

### 禁止 `<clinit>` 的规则

这是项目最重要的不变量。

朴素的生成器会把静态数组写进 `<clinit>`：

```java
static final Class<?>[] SERVICES = { HomeService.class, CameraService.class, ... };
```

在 Android 上，如果 `CameraService` 的实现位于尚未安装的动态模块中，那么在**类初始化期间**解析
该字面量会失败并抛出 `NoClassDefFoundError`。JVM 随后会在本进程余下的生命周期里把
`ServiceConfiguration` 标记为 erroneous。其他所有 provider 都会失效，之后再安装模块也无济于事。

因此生成器从不生成静态初始化块。所有类字面量都是方法体内的 `ldc` 指令，解析是惰性且可恢复的。
ASM `ClassWriter` 的 `getCommonSuperClass` 也被强制返回 `java/lang/Object`，因为默认实现会尝试在
构建 JVM 上加载那些实现类——而那些类可能根本不存在。

### 工厂方法

`@ProviderFactory` 标记一个返回实现类型的静态无参方法。生成器发出 `INVOKESTATIC`，其描述符在构建期
静态拼装，因此不需要反射调用 `Method.invoke`。扫描器会强制"必须是静态方法且最多一个"；无参和返回
类型这两项要求没有在构建期校验，违反者会在之后以 `NoSuchMethodError` 失败。

## 阶段 4：运行时探测

`ProviderRegistry` 在启动时刻意几乎什么都不做：

- 不预扫描。构造注册表只是保存配置。
- `load()` 直接调用 `newProvider()`。如果类不存在，`NoClassDefFoundError` 会被捕获并转换为
  `null`；下一次调用会重试。这就是新安装的动态模块无需任何 refresh 就能工作的原因。
- `MISSING` 从不缓存。`READY` 也不缓存；provider 按调用实例化，与 `ServiceLoader` 语义一致。
- `DEAD` **会**被缓存：一旦某个 provider 的 `<clinit>` 抛出异常，JVM 已把该类标记为 erroneous，
  重试不可能成功。
- `stateOf()` 按需探测，返回 `READY` / `MISSING` / `DEAD`。

门面在设计上是全函数。在 `initialize()` 之前调用任何查询只会打印一次警告并返回 `null` / 空集合。
生成类缺失或非法时会记录 error 并替换为空配置。这是有意的 API 决策：服务发现层不应该因为某个模块
缺失就让应用崩溃。

## 缓存与正确性

两个任务都标注了 `@CacheableTask`，并用 `PathSensitivity.RELATIVE` 声明源码输入，因此构建缓存条目
可重定位。设计文档中的验证矩阵覆盖：

- 干净构建全部四个变体，检查 APK/AAB 内容；
- 增量添加/移除一个 `@ServiceProvider`；
- 项目目录重定位后的构建缓存命中；
- 配置缓存的存储与复用，启用和不启用隔离项目各一次；
- 每个变体的真机自检。

最后一项不是装饰：它是唯一能发现"某个变体的生成类从未被追加"的手段，因为构建依然成功，而运行时
`ServiceConfiguration` 缺失。

## 取舍

- **仅限编译期。** provider 必须对构建可见。运行时加载的插件不在范围内。
- **每个 `(service, key)` 只能有一个 provider。** 冲突会让构建失败，而不是静默解析。
- **接口必须位于 base。** 如果把 service 接口放在动态模块里，安装前整张表都无法解析。
- **描述性，而非魔法。** 没有作用域、生命周期或构造函数注入。如果你的依赖图在构建期就已全部安装，
  请使用 Hilt 或 Dagger。`droid-spi` 服务于依赖图中*不保证*存在于进程内的那部分。

实现刻意做到一个下午就能读完：一个扫描器、一个 ASM writer、一个注册表和一个门面。困难的部分不是
代码，而是找出哪些 Gradle 与 JVM 的假设无法在动态下发和配置缓存下成立。

仓库：[Jayvinz/droid-spi](https://github.com/Jayvinz/droid-spi)。
设计文档：[`docs/droid-spi-design.zh-CN.md`](../droid-spi-design.zh-CN.md)。
