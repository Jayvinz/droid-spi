# Droid-spi · 设计方案

> 面向 AAB 多模块工程的**编译期 provider 配置表**。命名对齐 `java.util.ServiceLoader` 的 SPI 词汇。
> 实现代码位于 `Droid-spi` 仓库（`app/` + `modules/` 为 demo，
> `annotation` / `runtime` / `plugin` 为框架本体，单一 build）。
> 核心特征：**零反射——从不使用 `Class.forName`，provider 通过直接 `new` / `INVOKESTATIC`
> 构造，不使用任何反射构造函数或反射方法调用；零 APT/KSP；零第三方 jar 扫描；元数据以类字面量
> 表达；provider 按需探测**。
>
> English version: [droid-spi-design.md](droid-spi-design.md)
>
> 本文为英文设计文档的中文翻译；如与英文版有出入，以英文版为准。

---

## 目录

1. [目标与约束](#1-目标与约束)
2. [总体架构](#2-总体架构)
3. [编译期](#3-编译期)
4. [运行期](#4-运行期)
5. [关键 JVM 语义](#5-关键-jvm-语义)
6. [与主流方案对比](#6-与主流方案对比)
7. [边界与已知风险](#7-边界与已知风险)
8. [验证方式](#8-验证方式)

---

## 1. 目标与约束

### 1.1 要解决的问题

AAB 的依赖方向是单向的：动态功能模块依赖 base，而 base 在编译期**看不到**动态模块的类。
但业务上 base 需要调用动态模块提供的 service，且这些模块可能压根没装。

本框架提供：

1. base 侧写 `ServiceLoader.load(HomeService::class.java, "vip")` 就能拿到 provider；
2. provider 可以在常驻 library，也可以在 on-demand 动态模块；
3. 模块装好后下一次 `load()` 按需探测成功，`load()` 从返回 null 变成返回实例。

### 1.2 硬性约束（来自实际工程决策）

| 编号 | 约束 | 原因 |
|---|---|---|
| C1 | **零反射：不使用 `Class.forName`，也不对 provider 做反射构造或反射方法调用** | 反射加载类绕开 R8 可见性，失败抛 `Error` 难以治理。配置类用 compileOnly 占位类编译出直接 `new`；provider 用直接 `new`（`INVOKESPECIAL <init>`）或静态工厂（`INVOKESTATIC`）构造 |
| C2 | **不用 APT / KSP** | 大型工程里 processor 拖慢增量编译 |
| C3 | **不扫第三方 jar** | 只读自有源码，避免解压/重组数百 MB 的 AAR |
| C4 | **元数据尽量用类字面量** | 字符串类名 R8 不可见、重构不安全、拼错只能运行时发现 |
| C5 | **支持动态模块安装后类才可见** | Play Feature Delivery 的核心场景 |
| C6 | **不提供 `loadOrInstall` 之类复合 API** | 安装触发交给业务或 Play 自身流程，框架只负责"装好后下一次 load 能成功" |

`key` 是唯一保留的字符串维度：它是"同一接口的业务变体标签"（`""` / `"vip"` / `"guest"`），
不是类名，类型系统也表达不了。

> C3 的"不扫第三方 jar"不等于"不能集成第三方 provider"。做法是：第三方实现类仍在 jar / aar 里，
> 但把它按 JDK SPI 约定声明的 `META-INF/services/<service>` 文本**拷到本工程源码目录**
> （`src/main/resources/META-INF/services/`）即可 —— 只读我们自己的文本，不碰依赖产物（见 3.3）。

### 1.3 与标准 SPI 的词汇对照

命名刻意贴近 JDK SPI，便于迁移和理解：

| JDK `java.util.ServiceLoader` | 本工程 | 说明 |
|---|---|---|
| service | `HomeService` 接口 | 下沉在 base 的契约 |
| service provider | `HomeServiceImpl` | 各 feature 模块的实现 |
| provider-configuration file | `ServiceConfiguration`（主）／源码目录 `META-INF/services/`（兼容） | 编译期生成的 class 为主；标准 SPI 文本作为补充输入 |
| `ServiceLoader.load(service)` | `ServiceLoader.load(service, key)` | 多了业务变体 key |
| `ServiceLoader.Provider.type()/get()` | `ServiceLoader.Provider.type()/get()/state()` | 多了 provider 状态 |
| `ServiceLoader.stream()` | `ServiceLoader.stream()` / `providers()` | 语义一致；Kotlin 侧返回 `Sequence`，Java 侧用 `providers()` 返回 `List` |

差异点：`load()` 直接返回实例而不是 loader 对象；不可用时返回 `null`；多了 `key` 与 `ProviderState`。

---

## 2. 总体架构

```
┌─ Scan (per module variant) ──────────┐   ┌─ Aggregate (app, per variant) ──┐   ┌─ Runtime ──────────────┐
│ sources = src/main + flavor         │   │ compatible module manifests    │   │ Application.onCreate  │
│ / build-type overlays               │   │ ↓ merge + conflict check       │   │ ↓ initialize()        │
│ (*.kt | *.java)                     │   │ ServiceConfiguration.class     │   │ ↓ load() → READY      │
│ META-INF/services/**                │   │ ↓ toAppend(CLASSES)            │──▶│ ↓ INSTALLED           │
│ ↓ lexical scan                      │   │ base dex → APK/AAB             │   │ next load() → READY   │
│ services.txt                        │   └───────────────────────────────┘   └──────────────────────┘
│ service/provider/key/factory        │
└────────────────────────────────────┘
```

（图中标签保持英文，避免等宽字体下中英混排导致对齐错乱。）

三段职责非常干净：

- **扫描**：只读源码文本（注解源码 + 标准 SPI 配置文本），不依赖编译产物、不解压依赖产物，
  与 Kotlin/Java 编译顺序完全解耦；
- **聚合**：在 app 模块跑，输入是纯文本清单，输出是一个 class；
- **运行**：状态查询 + 按需探测，没有任何"按名字找类"的动作。

---

## 3. 编译期

### 3.1 注解

注解模块 `annotation` 是**纯 Java 模块**（`java-library`），保证纯 Java 项目能直接引用：

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface ServiceProvider {
    Class<?> service();
    String key() default "";
}

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface ProviderFactory { }
```

保留级别 `CLASS` —— 扫描发生在源码阶段，注解本身不进运行时，也不需要反射读取。

Kotlin 对 Java 注解**只接受命名参数**，所以 Kotlin 侧必须写 `service =`：

```kotlin
@ServiceProvider(service = HomeService::class)
class HomeServiceImpl : HomeService
```

Java 侧位置参数与命名参数都行：

```java
@ServiceProvider(HomeService.class)
public class HomeServiceImpl implements HomeService { }
```

扫描器同时兼容两种写法（`X::class` / `X::class.java` / `X.class`，带或不带 `service =` 前缀），
也接受跟在 service 后面的位置参数 key：`@ServiceProvider(HomeService.class, "vip")`。

**`@ProviderFactory` 的三条要求**：

1. 必须是静态方法（Kotlin 需 `@JvmStatic`，Java 需 `static`）；
2. 必须无参；
3. 返回类型必须是所在类自身。

扫描器会校验第 1 条，并限制一个类最多只能有一个 `@ProviderFactory`。第 2、3 条**不在构建期校验**：
生成的 `INVOKESTATIC provider.factory()L<provider>;` 描述符是静态拼接的，违反时会在运行时解析
该方法时出现描述符不匹配（见第 7 节风险表）。

第 3 条也是描述符能静态拼接的前提。放宽成父类型就要解析返回类型的全限定名，收益小、出错面大。

### 3.2 扫描器

不用裸正则，而是「注释剥离 + 字符串掩码」两级处理，两者都**保持字符偏移不变**：

| 处理 | 作用 | 用途 |
|---|---|---|
| `stripComments` | 注释替换为空格，字符串原样保留 | 读 `key = "vip"` 这类取值 |
| `maskStrings` | 字符串内容（含引号）替换为空格 | 结构扫描，避免字符串里的 `class` / `@ServiceProvider` 误判 |

流程：

```
定位 @ServiceProvider → 括号配对 → 切分顶层逗号
   ├─ 第 1 个参数：X::class / X::class.java / X.class → service 类型名
   └─ 其余：key = "..." 或位置参数 "..."
→ 从右括号向后找类声明（跳过字符串）→ provider 全限定名
→ 在类体内找 @ProviderFactory → 工厂方法名
```

**类型解析**：

1. 显式 `import a.b.C` → `a.b.C`（含 `as` 别名）；
2. 同包（本模块 TypeIndex 里确实声明过）；
3. 本模块内唯一同名类型；
4. 否则若存在 `import a.b.*` → **构建失败**（无法确定全限定名）；
5. 都没有则按同包处理。

第 4 步宁可失败不猜：猜错的全限定名不会在构建期暴露，只会在运行时变成 `NoClassDefFoundError`。

### 3.3 标准 SPI 配置兼容（META-INF/services）

第三方 jar / aar 里的实现类通常不会标 `@ServiceProvider`，但多数按 JDK SPI 约定声明了
`META-INF/services/<service 全限定名>`。为集成这类 provider，框架额外扫描**源码目录下**的同名文本文件：

```
your-module/src/main/resources/META-INF/services/com.example.core.api.LegacyService
```

解析器（`ServiceConfigScanner`）读文本、逐行取 provider 全限定名，`#` 起注释、空行忽略；
文件名即 service 全限定名（取路径中 `META-INF/services/` 之后的部分）。约束与映射：

| 项 | 处理 |
|---|---|
| 扫描目录 | 取该 variant 的全部静态源码目录（`variant.sources.java` / `kotlin` / `resources`），即 `src/main` 加上 build type / flavor 叠加目录；其下任意深度的 `META-INF/services/` 都识别 |
| 开关 | `spi { scanMetaInfServices = true }`，默认开启 |
| 范围 | 只读这些文本文件；**不解压 jar / aar、不扫 class**，延续 C3 |
| `key` | 标准 SPI 无此概念 → `""`（默认变体） |
| 实例化 | 无 factory → 无参构造，**实现类必须有公开无参构造** |
| 去重 | 与注解声明指向同一 provider（整行相同）时去重；不同实现抢同一**非空 key** 由聚合任务报错（空 key 允许多个实现） |
| 合法性 | provider 名不匹配 `^[\w.$]+$` 直接构建失败，给出文件名与行内容 |

每条配置映射成 `ProviderEntry(service, provider, "", "")`，之后与注解来源完全同路：
进入模块清单 → 聚合校验 → 生成 class 的 `providerAt` / `newProviderAt`。运行时无差别。

前置条件：该模块必须 apply `top.jayvin.droidspi` 插件（否则没有 `{variantName}ScanProviders` 任务）。
`modules/core-common` 就是只有 SPI 配置、没有注解的最小示例 —— 它的清单输出为：

```
com.example.core.api.LegacyService  com.example.core.common.LegacyServiceImpl
```

### 3.4 清单与聚合

每个模块每个 **variant** 一个 `ScanProvidersTask`（`freeDebugScanProviders`、`paidReleaseScanProviders` 等），
输出确定性清单（排序、无时间戳）：

```
com.example.core.api.AccountService	com.example.feature.account.AccountServiceImpl		create
com.example.core.api.AccountService	com.example.feature.account.GuestAccountServiceImpl	guest
```

四列：`service \t provider \t key \t factory`（`key` 为空串表示默认变体，`factory` 为空表示走无参构造）。

清单通过 Gradle 的 variant-aware 解析到达 app，而不是跨工程收集：

- 每个应用了插件的模块 variant 把清单以自定义类型 `spi-manifest` 追加到
  `{variant}RuntimeElements`；app 的生成任务对 `variant.runtimeConfiguration` 取按该类型过滤的
  artifact view，因此能（传递地）拿到所有应用了插件的运行时依赖的清单，没应用插件的模块不贡献文件；
- 动态特性不在 app 的运行时 classpath 上。app 对自身 `dynamicFeatures` DSL 里的每个工程声明依赖，
  并对它们的 runtime elements 取同样的按类型过滤的 artifact view。没有应用插件的动态特性不会携带
  清单 artifact，自然贡献 0 条 provider —— 不声明 provider 的特性没有必须应用插件的要求。

`GenerateServiceConfigurationTask` 按 app 的每个 variant 运行，校验每个**非空 key** 在每个 service
内唯一（冲突直接报错并列出两个 provider；空 key 允许多个实现），再生成 `ServiceConfiguration`
字节码并追加到该 variant 的
`ScopedArtifact.CLASSES`。由于清单是普通的任务输出 artifact，scan → generate 的边由 Gradle
自己维护，configuration cache 复用和 isolated projects 下都不会丢；若改成跨工程 `Provider`
或 root 扩展传递，configuration cache 复用时会退化为普通文件，scan 任务会静默从任务图消失。

两个任务都标注了 `@CacheableTask`；输入输出确定性，clean build 时可由 Gradle 从构建缓存恢复。

### 3.5 字节码生成（三个必须守住的点）

**① 不生成 `<clinit>`。** 所有类字面量只出现在方法体里：

```java
public Class providerAt(int index) {
    if (index == 0) return HomeServiceImpl.class;   // ldc 只在这里解析
    if (index == 1) return VipHomeServiceImpl.class;
    ...
    return null;
}
```

如果这些引用进了 `<clinit>`，配置类自己被标记 erroneous，之后即使模块装好也永远是
`Could not initialize class` —— 整表报废且不可恢复。

**② `services()` 里只放接口，且每个条目一个元素。** 它与 `keys()` 按下标一一对应：
同一接口注册 N 个 key 就在 `services()` 中出现 N 次（`ProviderRegistry` 按接口分组、按下标取 key）。
接口按约定下沉到 base，永远可解析。

**③ 走 `@ProviderFactory` 时生成 `INVOKESTATIC`**，而不是 `Method.invoke`：

```
15: invokestatic  // Method AccountServiceImpl.create:()LAccountServiceImpl;
```

工厂调用与 provider 构造都不走反射：没有 `getDeclaredMethod`，没有 `Constructor.newInstance`，
方法名也不参与混淆。没有 factory 的 provider 直接 `new` 后跟 `INVOKESPECIAL <init>`。

生成器用 `ClassWriter(COMPUTE_FRAMES)` 并重写 `getCommonSuperClass` 返回 `Object`——
provider 可能来自尚未安装的动态模块，生成期不能真的去加载它们。

### 3.6 AGP 接线

```kotlin
variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
    .use(generateTask)
    .toAppend(ScopedArtifact.CLASSES) { it.outputDir }
```

**为什么是 `toAppend` 而不是 `toTransform`**：生成任务的输入是纯文本清单，不读
`ScopedArtifact.CLASSES`，只往 classes 里追加一个 class，因此不存在"边读边写"的循环依赖，
也不需要把所有输入 class 重新打包一遍。该接线对**每个 variant** 各执行一次，而不是每个 build type
一次：flavor variant 共享 build type，但每个 variant 都需要自己的 classes 产物里有生成类。

**清单传递走 artifact view，而不是跨工程收集器**：用 root 扩展或共享 build service 保存其它工程的
`Provider<RegularFile>` 在所有任务都 up-to-date 时看不出问题，但在 configuration cache 复用
（尤其是 isolated projects）下，Gradle 会把跨工程 Provider 序列化成普通文件：scan 任务从任务图里
消失，陈旧的清单仍会传给生成器。把清单发布成带类型 artifact 后，Gradle 会用与 classes 相同的
variant 选择来解析它，producer 边得以保留。app 在 `projectsEvaluated` 时声明 `dynamicFeatures`
依赖（此时 DSL 值已确定）；声明依赖并不读取其它工程的模型，因此 isolated projects 保持干净。

---

## 4. 运行期

### 4.1 `ProviderConfiguration`：配置表契约

```kotlin
interface ProviderConfiguration {
    fun services(): Array<Class<*>>
    fun keys(): Array<String>
    fun providerAt(index: Int): Class<*>?   // 只解析，不触发 <clinit>
    fun newProviderAt(index: Int): Any?     // 解析 + 实例化
}
```

条目按 `services()` / `keys()` 的下标寻址，而不是按 `(service, key)`。把"类在不在"和"类能不能
初始化"拆成两个方法，是整套设计能区分 `MISSING`（模块没装，可重试）与 `DEAD`（`<clinit>` 失败，
不可逆）的前提。下标寻址还允许空 key 拥有多个实现：枚举时遍历所有下标，按 key 查找时取第一个
可用条目。

### 4.2 按需探测与 DEAD 缓存

实现：`runtime/src/main/kotlin/top/jayvin/android/spi/internal/ProviderRegistry.kt`。

不做预扫描，也不缓存 `MISSING`；`ProviderState` 只是 `stateOf()` 一次查询的结果：

| 状态 | 含义 | 进入条件 |
|---|---|---|
| `MISSING` | provider 类当前不可见 | `providerAt` 抛异常（例如 `NoClassDefFoundError`）或返回 null |
| `READY` | provider 类可解析 | `providerAt` 返回非 null |
| `DEAD` | 不可用且重试无意义 | `newProviderAt` 抛 `ExceptionInInitializerError`，或消息含 `Could not initialize class` |

同一 key 有多个默认实现时状态按聚合计算：任一 `READY` 即为 `READY`，否则任一 `MISSING` 即为
`MISSING`，全部 `DEAD` 才是 `DEAD`。

`load()` 遍历 `(service, key)` 的所有条目并直接调用 `newProviderAt()`：

- 成功 → 返回实例（第一个可用条目胜出）；
- `NoClassDefFoundError`（模块未装）→ 本次跳过该条目，**不缓存**，尝试下一个条目并在下次调用重试；
- `<clinit>` 失败 → 该条目下标加入 `dead` 集合，本进程内不再尝试；
- 工厂抛出的其他异常（业务异常）→ 不缓存，下次调用再试。

因此没有 `refresh()`，也不存在"装好了但表没更新"的窗口：模块装好后下一次 `load()` 自然成功。
`DEAD` 是唯一需要记住的状态，避免反复触发同一个初始化失败。

`newProviderAt` 抛 `Failed resolution of`（模块被卸载）时同样不缓存，下次再试。

`loadClass()` 与 `stateOf()` 把解析失败统一报为 `MISSING`，且不记录 `DEAD`：`DEAD` 只会在
`load()` / `newProviderAt()` 真正实例化 provider、从而观察到 erroneous `<clinit>` 时写入；
`DEAD` 按下标记录，因为同一空 key 可以有多个条目。

### 4.3 初始化入口：compileOnly 占位类

生成类是一个普通的 `final` 类，只实现 `ProviderConfiguration`；由应用在 `Application.onCreate`
里显式初始化：

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLoader.initialize()
    }
}
```

`configuration-stub` 模块提供一个同名空类 `top.jayvin.android.spi.generated.ServiceConfiguration`；
runtime 以 `compileOnly` 依赖它，编译出直接 `new` 的字节码：

```kotlin
val raw: Any = ServiceConfiguration()
val configuration = raw as? ProviderConfiguration ?: illegal()
```

运行时：

- app 插件生成了同名类 → ClassLoader 解析到真实配置表，直接 `new` 成功；
- 占位类不发布、不打包，不会与生成类重复；
- app 未 apply 插件、类不存在 → `new` 抛 `NoClassDefFoundError`，捕获后 `Log.e`，
  降级为空的 `ProviderConfiguration`；门面 API 仍可调用，只是返回 null / 空集合。

要点：

- provider 实现类仍由生成字节码里的 `ldc` / `new` / `INVOKESTATIC` 引用；配置类也是直接 `new`，
  全程没有 `Class.forName`，也没有对 provider 的反射构造或反射方法调用。
- R8 能看到 `new` 引用，存在时自动保留该类；对"未生成"的情况在 `consumer-rules.pro` 用 `-dontwarn`。
- 初始化时机由应用控制：`Application.onCreate` 早于任何 Activity，业务首次 `load()` 时配置表已就绪。
- 生成器仍不生成 `<clinit>`：若静态初始化里引用了未安装模块的类，配置类会立刻被标记 erroneous，整表报废。

### 4.4 动态模块可见性

框架不依赖 Play Core，也不调用 `SplitCompat`、不注册安装监听。动态模块安装完成后，类对当前进程
是否可见由宿主的安装通道决定（Play Feature Delivery 在 API 21+ 由平台处理；其他渠道由宿主自行
保证）。只要类可解析，下一次 `load()` 就会成功。

需要在 split 内访问自身资源或 Activity 的接入方，按 Play Core 文档自行处理 `SplitCompat`。

**不需要额外处理的场景**：冷启动（模块早装好）、进程被杀后重启（同上）。

非 Play 渠道下动态分发由宿主自己的通道负责；已经打进包里的 provider 仍然正常可用。

示例中 `:app` 依赖 Play Feature Delivery 并驱动 `SplitInstallManager`；`:modules:feature_camera`
就是 on-demand split。

### 4.5 对外门面

实现：`runtime/src/main/kotlin/top/jayvin/android/spi/ServiceLoader.kt`。

```kotlin
object ServiceLoader {
    const val DEFAULT_KEY = ""

    fun initialize()
    fun isInitialized(): Boolean

    fun <T : Any> load(service: Class<T>, key: String = DEFAULT_KEY): T?
    inline fun <reified T : Any> load(key: String = DEFAULT_KEY): T?
    fun <T : Any> loadClass(service: Class<T>, key: String = DEFAULT_KEY): Class<out T>?
    fun <T : Any> loadAll(service: Class<T>): Map<String, T>
    fun <T : Any> loadAllClasses(service: Class<T>): List<Class<out T>>

    // 对齐 java.util.ServiceLoader 的 Provider 体系
    fun <T : Any> providerOf(service: Class<T>, key: String = DEFAULT_KEY): Provider<T>?
    fun <T : Any> findFirst(service: Class<T>): Provider<T>?
    fun <T : Any> stream(service: Class<T>): Sequence<Provider<T>>
    fun <T : Any> providers(service: Class<T>): List<Provider<T>>

    fun stateOf(service: Class<*>, key: String = DEFAULT_KEY): ProviderState?
    fun snapshot(): List<Triple<String, String, ProviderState>>
}
```

`initialize()` 应先于任何查询执行，且是幂等的——重复调用不做任何事。门面永不抛异常：在
`initialize()` 之前调用查询方法会打印一次警告并返回 null / 空集合；`initialize()` 自身在生成类
缺失或配置无效时打印错误并降级为空配置。初始化完成后，服务不可用是常态，查询方法返回 null
（或空集合）而不抛异常。

除 inline 方法外，所有公开方法都有 `@JvmStatic`；Java 不支持 reified，用 Class 字面量调用；
`Sequence` 在 Java 里不适合直接遍历，因此额外提供 `providers()` 返回 `List`：

```java
HomeService home = ServiceLoader.load(HomeService.class);
HomeService vip  = ServiceLoader.load(HomeService.class, "vip");
for (Provider<HomeService> p : ServiceLoader.providers(HomeService.class)) {
    Log.d("spi", p.type().getName() + " / " + p.state());
}
```

Kotlin 侧则保留 `load<HomeService>()` 内联写法和 `stream()` 的 `Sequence`。

`load(service)` 带一条降级：未指定 key（空串）且该接口恰好只有一个可用变体时直接用它，
这样单实现接口不必写 key。

**刻意不提供的东西**：

| 不提供 | 原因 |
|---|---|
| `loadOrInstall` / `awaitService` | 安装是异步的，首调不可能拿到实例；混在一起会让返回值语义含糊（下载中 / 未注册 / 永不可用），还要维护跨模块等待队列。业务按需自己组合即可（见示例里的 `MainActivity.openCamera`） |
| 注解级 `singleton` | 缓存 key 是 provider 的 `Class`，split 卸载重装后旧实例会阻止旧 ClassLoader 回收。需要缓存的 provider 自己用 `@ProviderFactory` 返回缓存对象 |
| `callMethod(key, args...)` | 纯字符串定位方法与"元数据字面量化"冲突 |
| `getService(service, context)` | 依赖从 `@ProviderFactory` 方法体里取，避免"单例时 factory 只在首次生效"的坑 |

---

## 5. 关键 JVM 语义

**① `ldc X.class` 只解析、不初始化。**
所以 `providerAt` 天然把"类在不在 dex 里"与"类能不能正确初始化"分成两个阶段。

**② 类初始化失败是一次性的。**
`<clinit>` 抛错后类被标记 erroneous，后续任何访问都是 `Could not initialize class`，
进程内不可逆——所以 `ExceptionInInitializerError` 必须直接判 `DEAD`，不能重试。

**③ 符号引用解析失败不被缓存。**
模块装好后再执行同一条 `ldc`，常量池项仍是未解析状态，这次能找到类 → 成功。
这是"模块装好后下一次 `load()` 自动成功"的底层依据。

三条合起来才能推出：**字面量必须放方法体、放 `<clinit>` 会污染整表、失败后可以重试**。

---

## 6. 与主流方案对比

| 维度 | 本方案 | WMRouter | ARouter |
|---|---|---|---|
| 注册表生成 | 源码扫描 + 清单 + 生成字节码 | APT + Gradle 插件合并 | APT + 运行时扫 dex |
| 运行时扫描 | 无 | 无 | 有（扫 dex） |
| 反射 | 无（直接 `new` / `INVOKESTATIC`；无 `Class.forName`、无 `Constructor.newInstance`） | `Method.invoke` 调 provider | 反射建实例 |
| 动态模块支持 | provider 晚绑定：每次 `load()` 按需探测，`MISSING` 从不缓存；只记住不可逆的 `DEAD`（`<clinit>` 失败）；安装与 split 可见性由宿主负责（框架不依赖 Play Core） | 仅支持手工接入（`ServiceLoader.put()`） | 无 |
| 依赖 APT | 否 | 是 | 是 |
| 跨模块汇总 | 纯文本清单 | 生成类合并 | 路由表 + dex 扫描 |

“动态模块支持”的范围：框架只负责解析 split 安装后变得可见的 provider，不触发、不跟踪、也不强制安装。
示例中 `:app` 负责 Play Feature Delivery（`SplitInstallManager`）以及可能的 `SplitCompat` 调用；
`runtime` 不依赖 Play；`:modules:feature_camera` 就是 on-demand 示例 split。

WMRouter 的 `ServiceLoader.put()` 是 public static，可以手工接入动态模块，
但它的表存 `Class` 字面量且初始化一次性（`mHasInit`），
一旦某条 `ldc` 在 `<clinit>` 里失败就整个表直接失效——这也是本方案坚持"字面量不进 `<clinit>`"的直接原因。

---

## 7. 边界与已知风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 接口必须在 base | `services()` 的接口字面量若来自动态模块，整表失效 | 约定 + 代码评审（暂无构建期归属校验） |
| 接口未 import 时猜错包 | 全限定名错误不会在构建期暴露 | 存在 `import a.b.*` 时直接构建失败；其余要求显式 import |
| 扫描范围 | 按 variant 扫描其声明的静态源码（`src/main` + build type / flavor 叠加目录，来自 `variant.sources`）；生成的源码目录跳过；`META-INF/services` 也只认源码目录、不读依赖产物 | 已记录的行为；demo 的四变体自检验证覆盖 |
| `@ProviderFactory` 签名未校验 | 参数个数与返回类型未做构建期校验，违反时描述符不匹配、运行时才失败 | 工厂保持无参且返回所在类自身；违反时首次加载 provider 会抛 `NoSuchMethodError` |
| 按 variant 聚合 | 模块 variant 只有 build type 相同、且声明的 flavor 维度都与 app variant 一致时才被聚合；app 未声明的维度对应的依赖会被跳过（与 AGP 的匹配一致） | 已记录的行为；动态特性模块必须声明 base 模块的 flavor 维度 |
| 不支持嵌套类 / `typealias` | 扫描器按顶层类型解析 | 已记录的限制；service 请声明为顶层接口 |
| R8 missing class | base 引用未安装模块的类会告警 | 按模块补 `-dontwarn`（示例已给） |
| 非 Play 渠道 | 框架没有内建安装通道，split 可见性取决于宿主 | 由宿主实现自己的分发通道；已打进包里的 provider 保持 READY |
| 配置缓存 / isolated projects | 两者均支持：清单作为带类型 artifact 由 Gradle 的 variant 解析维护，configuration cache 复用后 producer 边仍存在；没有工程读取其它工程的模型。已实测存储/复用、源码变更、四个 variant 与 AAB | — |

---

## 8. 验证方式

以下验证均在本机执行：

| 项 | 命令 / 结果 |
|---|---|
| 工具链编译 | `./gradlew :plugin:compileKotlin` ✅ |
| 运行时单测 | `./gradlew :runtime:testDebugUnitTest` → JVM 上 9 个测试全部通过（注册表行为 + 门面不抛异常 / 降级测试） |
| 示例 APK | `:app:assembleFreeDebug` ✅；manifest 确认 Application；配置类由 `FreeDebugScanProviders` / `FreeDebugGenerateServiceConfiguration` 生成并打进 base dex |
| 示例 AAB | `:app:bundleFreeDebug` ✅，`base` / `feature_camera` 分包 |
| 变体矩阵 | `assembleFreeDebug` / `assemblePaidDebug` / `assembleFreeRelease` / `assemblePaidRelease` 四个组合全部 ✅（clean 后重建亦通过）；`javap` + `dexdump` 确认每个 APK 只含自己 flavor 的 `ChannelServiceImpl`，debug 包另有 `DebugServiceImpl`，release 包没有 |
| 真机 UI 测试 | 四个 APK 并存安装于小米 M2012K11C（Android 13）；每个自检页均为 `SELF-CHECK PASS 12/12`；截图见 `build/ui-test/`；点击"Re-run checks"与"Camera module"无崩溃（侧载 APK 无 Play 商店，split install 以 -14 错误优雅失败） |
| 增量构建 | 第二次 `:app:assembleFreeDebug` 时所有 `ScanProviders` / `GenerateServiceConfiguration` 任务均为 UP-TO-DATE，确认可缓存任务接线生效 |
| 增删 provider | 临时增删一个 provider 只重跑该模块的 scan + generate（及下游打包），其它模块 scan 保持 UP-TO-DATE；还原后任务 FROM-CACHE |
| Build cache | `clean` 后重建时所有 scan + generate 任务 FROM-CACHE；把工程按字节一致地复制到另一个绝对路径后同样全部 FROM-CACHE，验证了 RELATIVE 路径敏感度与确定性输出 |
| Configuration cache | 存储 + 复用通过；`--configuration-cache` 下改源码仍能重跑 scan/generate；四个 variant 一次构建；`:app:bundleFreeDebug` 通过 |
| Isolated projects | `-Dorg.gradle.unsafe.isolated-projects=true` 下，存储/复用、增删 provider、四个 variant 与 AAB 构建均通过 |
| 动态特性不接插件 | 从 `dynamicFeatures` 的某个工程移除插件后仍可正常构建（标准与 isolated 模式）；表中只是没有该特性的 provider，恢复插件后重新出现 |
| 生成字节码 | `javap -c` 确认：**无 `<clinit>`**、`providerAt` 只有 `ldc`、`newProviderAt` 对 `@ProviderFactory` 用 `INVOKESTATIC`；`services()` / `keys()` 按下标一一对应（debug 8 条、release 7 条） |
| 分包正确性 | `dexdump` 确认 `CameraServiceImpl` 只在 `feature_camera/dex`，base dex 只有引用 |
| 标准 SPI 配置 | `modules/core-common` 的 `META-INF/services/...LegacyService` 被扫描进清单（`LegacyService → LegacyServiceImpl`），生成字节码含 `ldc LegacyServiceImpl` 与 `new LegacyServiceImpl`，`MainActivity` 调用 `ServiceLoader.load(LegacyService::class.java)` 返回非空 |

### 8.1 完整回归矩阵（插件大改动后必须跑一遍）

扫描、聚合、artifact 接线或缓存机制有任何改动，都必须重跑本矩阵。以下步骤在 artifact 传递方案
重写后全部执行过（Gradle 8.9 / AGP 8.7.3）。

1. **框架发布 + JVM 单测**
   ```bash
   ./gradlew :annotation:publish :runtime:publish :plugin:publish
   ./gradlew :runtime:testDebugUnitTest --rerun-tasks
   ```
2. **clean 构建全部变体**（同时验证 AAB）
   ```bash
   ./gradlew clean :app:assembleFreeDebug :app:assemblePaidDebug :app:assembleFreeRelease :app:assemblePaidRelease
   ./gradlew :app:bundleFreeDebug
   ```
3. **逐变体检查生成类**（`javap -p -c`）：只含本 flavor 的 `ChannelServiceImpl`；debug 有
   `DebugServiceImpl`、release 没有；`services()` 与 `keys()` 长度相等（debug 8 / release 7）；
   无 `<clinit>`。
4. **APK/AAB 内容**（`dexdump`）：`CameraServiceImpl` 只在 feature split 中；任何 APK/AAB 都
   不应出现 `services.txt` artifact。
5. **增量**：给某个模块临时加一个 `@ServiceProvider`，构建后确认只有该模块的 scan + generate
   重跑；删除后确认 FROM-CACHE 且生成类恢复。
6. **Build cache**：`clean` 重建时所有 scan + generate 均 FROM-CACHE；把工程按字节一致地复制到
   另一个绝对路径后同样 FROM-CACHE。
7. **Configuration cache（标准）**：存储、复用；在复用状态下增删 provider，scan + generate 必须
   重跑。再用一次构建四个变体、以及 `:app:bundleFreeDebug` 各验一遍。
8. **Isolated projects**：用 `-Dorg.gradle.unsafe.isolated-projects=true`（Gradle 9.7+ 用
   `--isolated-projects`）重复第 6–7 步。
9. **动态特性容错**：从某个 `dynamicFeatures` 工程移除插件，构建仍须成功且该特性贡献 0 条
   provider；恢复插件后条目重新出现。
10. **真机矩阵**：安装四个 APK 并逐个启动，确认 `SELF-CHECK PASS 12/12` 及变体相关值
    （flavor provider、buildType 隔离、条目数）；点击 "Re-run checks" 与 "Camera module"
    （侧载 APK 上 split install 以 -14 优雅失败）；截图保存在 `build/ui-test/`，并确认没有
    `AndroidRuntime` 异常。

已知缺口：真实 Play 动态安装后的 `MISSING → READY` 切换无法用侧载 APK 验证；运行时单测用假
`ProviderConfiguration` 覆盖了等价的状态迁移。
