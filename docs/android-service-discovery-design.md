# Android SPI ServiceLoader · 设计方案（v2，已落地）

> AAB 多模块工程下的编译期 provider 配置表。代码命名对齐 `java.util.ServiceLoader` 的 SPI 词汇。
> 实现代码：`android-spi/`，示例工程：`android-spi/`（`app/` + `modules/` 为 demo，`annotation` / `runtime` / `plugin` 为插件本体，单一 build）。
> 核心特征：**零 `Class.forName`（配置类与 provider 都用类字面量）、零 APT/KSP、零第三方 jar 扫描、元数据类字面量化、provider 按需探测**。

---

## 1. 目标与约束

### 1.1 要解决的问题

AAB 的依赖方向是单向的：动态功能模块依赖 base，base 在编译期**看不到**动态模块的类。
但业务上 base 需要调用动态模块提供的 service，且模块可能压根没装。

本框架提供：

1. base 侧写 `ServiceLoader.load(HomeService::class.java, "vip")` 就能拿到 provider；
2. provider 可以在常驻 library，也可以在 on-demand 动态模块；
3. 模块装好后下一次 `load()` 按需探测成功，`load()` 从返回 null 变成返回实例。

### 1.2 硬性约束（来自实际工程决策）

| 编号 | 约束 | 原因 |
|---|---|---|
| C1 | **不用 `Class.forName`** | 反射加载类绕开 R8 可见性，失败抛 `Error` 难以治理；配置类用 compileOnly 占位类编译出直接 `new` |
| C2 | **不用 APT / KSP** | 大型工程里 processor 拖慢增量编译 |
| C3 | **不扫第三方 jar** | 只读自有源码，避免解压/重组数百 MB AAR |
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
┌─ 编译期（每个模块） ─────────────┐   ┌─ 聚合期（仅 app） ────────────┐   ┌─ 运行期 ─────────────┐
│ src/main/**/*.kt|java            │   │  services.txt（全部模块）      │   │ Application.onCreate │
│   ↓ 词法扫描 + 类型解析          │   │   ↓ 合并 + 冲突校验            │   │   ↓ initialize()    │
│ src/main/resources/META-INF/     │   │ ServiceConfiguration.class                 │──▶│ 按需 load() → READY  │
│   services/**  ↓ 标准 SPI 解析   │──▶│   ↓ toAppend(CLASSES)         │   │   ↓ INSTALLED        │
│ services.txt（service/provider/  │   │ base dex → APK/AAB            │   │ 下次 load() → READY  │
│           key/factory）          │   │                               │   │                      │
└──────────────────────────────────┘   └───────────────────────────────┘   └──────────────────────┘
```

三段职责非常干净：

- **扫描**：只读源码文本（注解源码 + 标准 SPI 配置文本），不依赖编译产物、不解压依赖产物，与 Kotlin/Java 编译顺序完全解耦；
- **聚合**：在 app 模块跑，输入是纯文本清单，输出是一个 class；
- **运行**：状态表 + 安装监听，没有任何"按名字找类"的动作。

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

保留级别 `CLASS`——扫描发生在源码阶段，注解本身不进运行时，也不需要反射读取。

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

扫描器同时兼容两种写法（`X::class` / `X::class.java` / `X.class`，带或不带 `service =` 前缀）。

**`@ProviderFactory` 的三条硬约束**（构建期校验）：

1. 必须是静态方法（Kotlin 需 `@JvmStatic`，Java 需 `static`）；
2. 必须无参；
3. 返回类型必须是所在类自身。

第三条是为了让生成的 `INVOKESTATIC provider.factory()L<provider>;` 描述符可以静态拼接。
放宽成父类型就要解析返回类型的全限定名，收益小、出错面大。

### 3.2 扫描器

不用裸正则，而是「注释剥离 + 字符串掩码」两级处理，两者都**保持字符偏移不变**：

| 处理 | 作用 | 用途 |
|---|---|---|
| `stripComments` | 注释替换为空格，字符串原样保留 | 读 `key = "vip"` 这类取值 |
| `maskStrings` | 字符串内容（含引号）替换为空格 | 结构扫描，避免字符串里的 `class` / `@ServiceProvider` 误判 |

流程：

```
@ServiceProvider 定位 → 括号配对取参数 → 切分顶层逗号
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
| 扫描目录 | `src/main/resources` 加入 `sourceDirs`；也兼容 `src/main/java` / `src/main/kotlin` 下任意深度的 `META-INF/services/` |
| 开关 | `spi { scanMetaInfServices = true }`，默认开启 |
| 范围 | 只读这些文本文件；**不解压 jar / aar、不扫 class**，延续 C3 |
| `key` | 标准 SPI 无此概念 → `""`（默认变体） |
| 实例化 | 无 factory → 无参构造，**实现类必须有公开无参构造** |
| 去重 | 与注解声明指向同一 provider（整行相同）时去重；不同实现抢同一 `(service, key)` 由聚合任务报错 |
| 合法性 | provider 名不匹配 `^[\w.$]+$` 直接构建失败，给出文件名与行内容 |

每条配置映射成 `ProviderEntry(service, provider, "", "")`，之后与注解来源完全同路：
进入模块清单 → 聚合校验 → 生成 class 的 `providerOf` / `newProvider`。运行时无差别。

前置条件：该模块必须 apply `top.jayvin.android.spi` 插件（否则没有 `xxScanProviders` 任务）。
`modules/core-common` 就是只有 SPI 配置、没有注解的最小示例 —— 它的清单输出为：

```
com.example.core.api.LegacyService  com.example.core.common.LegacyServiceImpl
```

### 3.4 清单与聚合

每个模块一个 `ScanProvidersTask`，输出确定性清单（排序、无时间戳）：

```
com.example.core.api.AccountService	com.example.feature.account.AccountServiceImpl		create
com.example.core.api.AccountService	com.example.feature.account.GuestAccountServiceImpl	guest
```

四列：`service \t provider \t key \t factory`（`key` 为空串表示默认变体，`factory` 为空表示走无参构造）。

`GenerateServiceConfigurationTask` 在 app 模块合并全部清单：校验 `(service, key)` 全局唯一
（冲突直接报错并列出两个 provider），然后生成 `ServiceConfiguration` 字节码。

### 3.5 字节码生成（三个必须守住的点）

**① 不生成 `<clinit>`。** 所有类字面量只出现在方法体里：

```java
public Class providerOf(Class service, String key) {
    if (service == HomeService.class) {
        if ("".equals(key)) return HomeServiceImpl.class;   // ldc 只在这里解析
        ...
    }
    return null;
}
```

如果这些引用进了 `<clinit>`，配置类自己被标记 erroneous，
之后即使模块装好也永远是 `Could not initialize class` —— 整表报废且不可恢复。

**② `services()` 里只放接口。** 接口按约定下沉到 base，永远可解析。

**③ 走 `@ProviderFactory` 时生成 `INVOKESTATIC`**，而不是 `Method.invoke`：

```
15: invokestatic  // Method AccountServiceImpl.create:()LAccountServiceImpl;
```

零反射、无 `getDeclaredMethod`、方法名不参与混淆。

生成器用 `ClassWriter(COMPUTE_FRAMES)` 并重写 `getCommonSuperClass` 返回 `Object`——
provider 可能来自尚未安装的动态模块，生成期不能真的去加载它们。

### 3.6 AGP 接线

```kotlin
variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
    .use(generateTask)
    .toAppend(ScopedArtifact.CLASSES) { it.outputDir }
```

**为什么是 `toAppend` 而不是 `toTransform`**：
生成任务的输入是纯文本清单，不读 `ScopedArtifact.CLASSES`，只往 classes 里追加一个 class，
因此不存在"边读边写"的循环依赖，也不需要把所有输入 class 重新打包一遍。

**跨工程收集用 `projectsEvaluated` 而不是配置期 `findByName`**：
Gradle 按 settings 顺序逐个 evaluate 工程，app 可能早于 feature 模块被配置，
此时 `findByName("DebugScanProviders")` 返回 null 会静默漏掉整个模块。
每个模块把自己的 `Provider<RegularFile>` 登记到 root extension，
app 在 `projectsEvaluated` 时一次性接上——此时所有工程都已配置完成，且 Provider 携带 producer 依赖，
任务图里自动生成 scan → generate 的边。

---

## 4. 运行期

### 4.1 `ProviderConfiguration`：配置表契约

```kotlin
interface ProviderConfiguration {
    fun services(): Array<Class<*>>
    fun keys(): Array<String>
    fun providerOf(service: Class<*>, key: String): Class<*>?   // 只解析，不触发 <clinit>
    fun newProvider(service: Class<*>, key: String): Any?         // 解析 + 实例化
}
```

把"类在不在"和"类能不能初始化"拆成两个方法，是整套设计能区分
`MISSING`（模块没装，可重试）与 `DEAD`（`<clinit>` 失败，不可逆）的前提。

### 4.2 按需探测与 DEAD 缓存

不做预扫描，也不缓存 `MISSING`；`ProviderState` 只是 `stateOf()` 一次查询的结果：

| 状态 | 含义 | 进入条件 |
|---|---|---|
| `MISSING` | provider 类当前不可见 | `providerOf` 抛 `NoClassDefFoundError` 或返回 null |
| `READY` | provider 类可解析 | `providerOf` 返回非 null |
| `DEAD` | 不可用且重试无意义 | `newProvider` 抛 `ExceptionInInitializerError`，或消息含 `Could not initialize class` |

`load()` 直接调用 `newProvider()`：

- 成功 → 返回实例；
- `NoClassDefFoundError`（模块未装）→ 返回 null，**不缓存**，下次调用再试；
- `<clinit>` 失败 → 加入 `dead` 集合，本进程内不再尝试。

因此没有 `refresh()`，也不存在"装好了但表没更新"的窗口：模块装好后下一次 `load()` 自然成功。
`DEAD` 是唯一需要记住的状态，避免反复触发同一个初始化失败。

`newProvider` 抛 `Failed resolution of`（模块被卸载）时同样不缓存，下次再试。

### 4.3 初始化入口：compileOnly 占位类

生成类是一个普通的 `final` 类，只实现 `ProviderConfiguration`；由应用在 `Application.onCreate` 里显式初始化：

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
  全程没有 `Class.forName`。
- R8 能看到 `new` 引用，存在时自动保留该类；对"未生成"的情况在 `consumer-rules.pro` 用 `-dontwarn`。
- 初始化时机由应用控制：`Application.onCreate` 早于任何 Activity，业务首次 `load()` 时配置表已就绪。
- 生成器仍不生成 `<clinit>`：若静态初始化里引用了未安装模块的类，配置类会立刻被标记 erroneous，整表报废。

### 4.4 动态模块可见性

框架不依赖 Play Core，也不调用 `SplitCompat`、不注册安装监听。动态模块安装完成后，类对当前进程是否可见
由宿主的安装通道决定（Play Feature Delivery 在 API 21+ 由平台处理；其他渠道由宿主自行保证）。
只要类可解析，下一次 `load()` 就会成功。

需要在 split 内访问自身资源或 Activity 的接入方，按 Play Core 文档自行处理 `SplitCompat`。

**不需要额外处理的场景**：冷启动（模块早装好）、进程被杀后重启（同上）。

非 Play 渠道下动态分发由宿主自己的通道负责；已经打进包里的 provider 仍然正常可用。

### 4.5 对外门面

```kotlin
object ServiceLoader {
    fun <T : Any> load(service: Class<T>, key: String = ""): T?        // 默认 / 指定变体
    inline fun <reified T : Any> load(key: String = ""): T?
    fun <T : Any> loadClass(service: Class<T>, key: String = ""): Class<out T>?
    fun <T : Any> loadAll(service: Class<T>): Map<String, T>
    fun <T : Any> loadAllClasses(service: Class<T>): List<Class<out T>>

    // 对齐 java.util.ServiceLoader 的 Provider 体系
    fun <T : Any> providerOf(service: Class<T>, key: String = ""): Provider<T>?
    fun <T : Any> findFirst(service: Class<T>): Provider<T>?
    fun <T : Any> stream(service: Class<T>): Sequence<Provider<T>>

    fun stateOf(service: Class<*>, key: String): ProviderState?
}
```

全部返回 `null` 而不抛异常：服务不可用是常态，不是错误。

Java 侧所有公开方法都有 `@JvmStatic`，但 Java 不支持 reified，用 Class 字面量调用；
`Sequence` 在 Java 里不适合直接遍历，额外提供 `providers()` 返回 `List`：

```java
HomeService home = ServiceLoader.load(HomeService.class);
HomeService vip  = ServiceLoader.load(HomeService.class, "vip");
for (Provider<HomeService> p : ServiceLoader.providers(HomeService.class)) {
    Log.d("spi", p.type().getName() + " / " + p.state());
}
```

Kotlin 侧则保留 `load<HomeService>()` 内联写法和 `stream()` 的 `Sequence`。

`load(service)` 带一条降级：未指定 key（空串）且该接口只有一个 READY provider 时直接用它，
这样单实现接口不必写 key。

**刻意不提供的东西**：

| 不提供 | 原因 |
|---|---|
| `loadOrInstall` / `awaitService` | 安装是异步的，首调不可能拿到实例；混在一起会让返回值语义含糊（下载中 / 未注册 / 永不可用），还要维护跨模块等待队列。业务按需自己组合即可（示例 app 里 6 行） |
| 注解级 `singleton` | 缓存 key 是 provider 的 `Class`，split 卸载重装后旧实例会阻止旧 ClassLoader 回收。需要缓存的 provider 自己用 `@ProviderFactory` 返回缓存对象 |
| `callMethod(key, args...)` | 纯字符串定位方法与"元数据字面量化"冲突 |
| `getService(service, context)` | 依赖从 `@ProviderFactory` 方法体里取，避免"单例时 factory 只在首次生效"的坑 |

---

## 5. 关键 JVM 语义（决定设计能不能成立）

**① `ldc X.class` 只解析、不初始化。**
所以 `providerOf` 天然把"类在不在 dex 里"与"类能不能正确初始化"分成两个阶段。

**② 类初始化失败是一次性的。**
`<clinit>` 抛错后类被标记 erroneous，后续任何访问都是 `Could not initialize class`，
进程内不可逆——所以 `ExceptionInInitializerError` 必须直接判 `DEAD`，不能重试。

**③ 符号引用解析失败不被缓存。**
模块装好后再执行同一条 `ldc`，常量池项仍是未解析状态，这次能找到类 → 成功。
这是"模块装好后下一次 `load()` 自动成功"的底层依据。

三条合起来才能推出：**字面量必须放方法体、放 `<clinit>` 会 poison 整表、失败后可以重试**。

---

## 6. 与主流方案对比

| 维度 | 本方案 | WMRouter | ARouter |
|---|---|---|---|
| 注册表生成 | 源码扫描 + 清单 + 生成字节码 | APT + Gradle 插件合并 | APT + 运行时扫 dex |
| 运行时扫描 | 无 | 无 | 有（扫 dex） |
| 反射 | 无（compileOnly 占位类 + 直接 `new`；provider 用类字面量） | `Method.invoke` 调 provider | 反射建实例 |
| 动态模块支持 | 内建（按需探测 + DEAD 缓存） | 无 | 无 |
| 依赖 APT | 否 | 是 | 是 |
| 跨模块汇总 | 纯文本清单 | 生成类合并 | 路由表 + dex 扫描 |

WMRouter 的 `ServiceLoader.put()` 是 public static，可以手工接入动态模块，
但它的表存 `Class` 字面量且初始化一次性（`mHasInit`），
一旦某条 `ldc` 在 `<clinit>` 里失败就整表 fatal——这也是本方案坚持"字面量不进 `<clinit>`"的直接原因。

---

## 7. 边界与已知风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 接口必须在 base | `services()` 的接口字面量若来自动态模块，整表失效 | 约定 + 接入评审；v2 加构建期归属校验 |
| 接口未 import 时猜错包 | 全限定名错误不会在构建期暴露 | 存在 `import a.b.*` 时直接构建失败；其余要求显式 import |
| 只扫 `src/main` | flavor 目录、自定义 sourceSet 未覆盖；`META-INF/services` 也只认源码目录、不读依赖产物 | v2 接 `variant.sources` |
| 按 buildType 聚合 | flavor 独有服务不会进表 | 多 flavor 工程需按 variant 对齐 |
| 不支持嵌套类 / typealias | 扫描器按顶层类型解析 | 构建期报错，不静默 |
| R8 missing class | base 引用未安装模块的类会告警 | 按模块补 `-dontwarn`（示例已给） |
| 非 Play 渠道 | 动态分发不生效 | 退化为静态配置表，所有 provider 天然 READY |
| 配置缓存 | 用了 `projectsEvaluated` 与跨工程 Provider | 迁移到 isolated projects 前不要开 configuration cache |

---

## 8. 验证方式（已在本机执行）

| 项 | 命令 / 结果 |
|---|---|
| 工具链编译 | `./gradlew :plugin:compileKotlin` ✅ |
| 运行时单测 | `./gradlew :runtime:testDebugUnitTest` → 7 passed（未装返回 null、安装后无需 refresh 直接成功、每次 load 重试、DEAD 不重试、未注册 key、stream 不实例化等） |
| 示例 APK | `:app:assembleDebug` ✅，manifest 确认 Application；配置类由 `initDebugScanProviders` / `DebugGenerateServiceConfiguration` 生成并打进 base dex |
| 示例 AAB | `:app:bundleDebug` ✅，`base` / `feature_camera` 分包 |
| 生成字节码 | `javap -c` 确认：**无 `<clinit>`**、`providerOf` 只有 `ldc`、`newProvider` 对 `@ProviderFactory` 用 `INVOKESTATIC` |
| 分包正确性 | `dexdump` 确认 `CameraServiceImpl` 只在 `feature_camera/dex`，base dex 只有引用 |
| 标准 SPI 配置 | `modules/core-common` 的 `META-INF/services/...LegacyService` 被扫描进清单（`LegacyService → LegacyServiceImpl`），生成字节码含 `ldc LegacyServiceImpl` 与 `new LegacyServiceImpl`，`MainActivity` 调 `ServiceLoader.load(LegacyService::class.java)` 返回非空 |
